package com.bruno.kota.services;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bruno.kota.dtos.QuotationImportResult;
import com.bruno.kota.dtos.QuotationResponse;
import com.bruno.kota.entities.ImportProfile;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ImportProfileRepository;
import com.bruno.kota.repositories.ProductRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;
import com.bruno.kota.repositories.SupplierGroupRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuotationImportService {

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final ProductRepository productRepository;
    private final ImportProfileRepository importProfileRepository;
    private final SupplierGroupRepository supplierGroupRepository;

    @Transactional
    public QuotationImportResult importFile(
            MultipartFile file,
            String name,
            Long supplierGroupId,
            String expirationDate,
            Integer descriptionColumnOverride,
            Integer barcodeColumnOverride,
            Integer quantityColumnOverride) {

        List<CSVRecord> rows;
        List<String> headers;

        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setDelimiter(';')
                    .build()
                    .parse(reader);

            // Cabeçalho é lido "na mão" em vez de deixar o Commons CSV detectar
            // automaticamente (.setHeader()) — o parser automático explode com
            // IllegalArgumentException assim que encontra uma célula de cabeçalho
            // vazia (duas colunas sem nome colidem como "nome duplicado: \"\"").
            // Como o resto do código já lê cada linha por índice de coluna
            // (row.get(descriptionColumn), não por nome), não precisamos do
            // cabeçalho nomeado do Commons CSV pra nada além de exibir a lista de
            // colunas na tela de mapeamento — então uma coluna sem descrição vira
            // só um rótulo genérico "Coluna N", sem travar a importação.
            List<CSVRecord> allRecords = parser.getRecords();
            if (allRecords.isEmpty()) {
                throw new BusinessRuleException("Arquivo CSV vazio.");
            }

            CSVRecord headerRecord = allRecords.get(0);
            headers = new ArrayList<>();
            for (int i = 0; i < headerRecord.size(); i++) {
                String headerValue = headerRecord.get(i).trim();
                headers.add(headerValue.isEmpty() ? "Coluna " + (i + 1) : headerValue);
            }
            rows = allRecords.subList(1, allRecords.size());
        } catch (IOException e) {
            throw new BusinessRuleException("Não foi possível ler o arquivo: " + e.getMessage());
        }

        String headerSignature = String.join("|", headers);

        Integer descriptionColumn = descriptionColumnOverride;
        Integer barcodeColumn = barcodeColumnOverride;
        Integer quantityColumn = quantityColumnOverride;

        boolean mappingProvided = descriptionColumn != null && barcodeColumn != null && quantityColumn != null;

        if (!mappingProvided) {
            var existingProfile = importProfileRepository.findTopByOrderByUpdatedAtDesc();

            if (existingProfile.isPresent() && existingProfile.get().getHeaderSignature().equals(headerSignature)) {
                ImportProfile profile = existingProfile.get();
                descriptionColumn = profile.getDescriptionColumn();
                barcodeColumn = profile.getBarcodeColumn();
                quantityColumn = profile.getQuantityColumn();
            } else {
                return new QuotationImportResult(true, headers, null);
            }
        }

        validateColumnIndex(descriptionColumn, headers.size(), "descrição");
        validateColumnIndex(barcodeColumn, headers.size(), "código de barras");
        validateColumnIndex(quantityColumn, headers.size(), "quantidade");

        SupplierGroup supplierGroup = resolveSupplierGroup(supplierGroupId);
        LocalDateTime parsedExpirationDate = parseExpirationDate(expirationDate);

        Quotation quotation = Quotation.builder()
                .name(name)
                .supplierGroup(supplierGroup)
                .expirationDate(parsedExpirationDate)
                .build();
        quotation = quotationRepository.save(quotation);

        for (CSVRecord row : rows) {
            String description = row.get(descriptionColumn).trim();
            String barcode = row.get(barcodeColumn).trim();
            String rawQuantity = row.get(quantityColumn).trim();

            if (barcode.isEmpty()) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": código de barras vazio.");
            }

            BigDecimal quantity;
            try {
                quantity = new BigDecimal(rawQuantity.replace(",", "."));
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": quantidade inválida (\"" + rawQuantity + "\").");
            }

            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": quantidade deve ser maior que zero.");
            }

            Product product = productRepository.findByBarcode(barcode)
                    .orElseGet(() -> productRepository.save(
                            Product.builder()
                                    .barcode(barcode)
                                    .name(description)
                                    .build()
                    ));

            QuotationItem item = QuotationItem.builder()
                    .quotation(quotation)
                    .product(product)
                    .quantity(quantity)
                    .build();
            quotationItemRepository.save(item);
        }

        saveOrUpdateProfile(descriptionColumn, barcodeColumn, quantityColumn, headerSignature);

        return new QuotationImportResult(false, null, toQuotationResponse(quotation));
    }

    private SupplierGroup resolveSupplierGroup(Long supplierGroupId) {
        if (supplierGroupId == null) {
            return null;
        }
        return supplierGroupRepository.findById(supplierGroupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + supplierGroupId));
    }

    private LocalDateTime parseExpirationDate(String expirationDate) {
        if (expirationDate == null || expirationDate.isBlank()) {
            return null;
        }
        // <input type="datetime-local"> manda "yyyy-MM-ddTHH:mm" (16 caracteres), sem segundos.
        // LocalDateTime.parse exige segundos no formato ISO — completa se estiver faltando.
        String normalized = expirationDate.length() == 16 ? expirationDate + ":00" : expirationDate;
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("Prazo de expiração inválido: " + expirationDate);
        }
    }

    private void validateColumnIndex(Integer column, int headerCount, String fieldLabel) {
        if (column == null || column < 0 || column >= headerCount) {
            throw new BusinessRuleException("Índice de coluna inválido para " + fieldLabel + ".");
        }
    }

    private void saveOrUpdateProfile(Integer descriptionColumn, Integer barcodeColumn, Integer quantityColumn, String headerSignature) {
        ImportProfile profile = importProfileRepository.findTopByOrderByUpdatedAtDesc()
                .orElseGet(() -> ImportProfile.builder().build());

        profile.setDescriptionColumn(descriptionColumn);
        profile.setBarcodeColumn(barcodeColumn);
        profile.setQuantityColumn(quantityColumn);
        profile.setHeaderSignature(headerSignature);

        importProfileRepository.save(profile);
    }

    private QuotationResponse toQuotationResponse(Quotation quotation) {
        SupplierGroup group = quotation.getSupplierGroup();
        return new QuotationResponse(
                quotation.getId(),
                quotation.getName(),
                quotation.getStatus(),
                group != null ? group.getId() : null,
                group != null ? group.getName() : null,
                quotation.getCreatedAt(),
                quotation.getPublishedAt(),
                quotation.getExpirationDate(),
                quotation.getUpdatedAt()
        );
    }
}