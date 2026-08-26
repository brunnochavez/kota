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
import com.bruno.kota.entities.QuotationEvent;
import com.bruno.kota.entities.QuotationEventType;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ImportProfileRepository;
import com.bruno.kota.repositories.ProductRepository;
import com.bruno.kota.repositories.QuotationEventRepository;
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
    private final QuotationEventRepository quotationEventRepository;

    @Transactional
    public QuotationImportResult importFile(
            MultipartFile file,
            String name,
            Long supplierGroupId,
            String expirationDate,
            Integer defaultSalesProjectionDays,
            Integer descriptionColumnOverride,
            Integer barcodeColumnOverride,
            Integer quantityColumnOverride,
            boolean includeCostPrices,
            Integer costColumnOverride,
            String performedBy) {

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
        // includeCostPrices=false zera qualquer costColumn que por acaso tenha chegado —
        // "incluir preço de custo" é uma decisão tomada a cada importação (não fica
        // lembrada sozinha só porque o perfil de mapeamento tem uma coluna de custo
        // salva de uma importação anterior).
        Integer costColumn = includeCostPrices ? costColumnOverride : null;

        boolean mappingProvided = descriptionColumn != null && barcodeColumn != null && quantityColumn != null;

        if (!mappingProvided) {
            var existingProfile = importProfileRepository.findTopByOrderByUpdatedAtDesc();
            boolean profileMatches = existingProfile.isPresent() && existingProfile.get().getHeaderSignature().equals(headerSignature);
            // Só reaproveita o perfil salvo sem perguntar de novo se ele já resolve TUDO
            // que essa importação precisa — inclusive a coluna de custo, quando pedida.
            // Sem essa checagem, marcar "incluir preço de custo" numa planilha cujo
            // layout já tinha perfil salvo (mas sem coluna de custo mapeada ainda)
            // importaria silenciosamente sem custo nenhum, sem nunca perguntar qual coluna usar.
            boolean profileSatisfiesCost = !includeCostPrices || (profileMatches && existingProfile.get().getCostColumn() != null);

            if (profileMatches && profileSatisfiesCost) {
                ImportProfile profile = existingProfile.get();
                descriptionColumn = profile.getDescriptionColumn();
                barcodeColumn = profile.getBarcodeColumn();
                quantityColumn = profile.getQuantityColumn();
                costColumn = includeCostPrices ? profile.getCostColumn() : null;
            } else {
                return new QuotationImportResult(true, headers, null);
            }
        }

        validateColumnIndex(descriptionColumn, headers.size(), "descrição");
        validateColumnIndex(barcodeColumn, headers.size(), "código de barras");
        validateColumnIndex(quantityColumn, headers.size(), "quantidade");
        if (includeCostPrices) {
            validateColumnIndex(costColumn, headers.size(), "preço de custo");
        }

        // As colunas mapeadas precisam ser fisicamente diferentes entre si — mapear a
        // mesma coluna duas vezes (ex: código de barras e descrição apontando pra
        // coluna 2) é sempre erro de mapeamento, nunca uma escolha válida.
        if (descriptionColumn.equals(barcodeColumn) || descriptionColumn.equals(quantityColumn) || barcodeColumn.equals(quantityColumn)) {
            throw new BusinessRuleException("As colunas de Descrição, Código de Barras e Quantidade precisam ser diferentes entre si — confira o mapeamento.");
        }
        if (costColumn != null && (costColumn.equals(descriptionColumn) || costColumn.equals(barcodeColumn) || costColumn.equals(quantityColumn))) {
            throw new BusinessRuleException("A coluna de Preço de Custo precisa ser diferente das demais — confira o mapeamento.");
        }

        SupplierGroup supplierGroup = resolveSupplierGroup(supplierGroupId);
        LocalDateTime parsedExpirationDate = parseExpirationDate(expirationDate);

        Quotation quotation = Quotation.builder()
                .name(name)
                .supplierGroup(supplierGroup)
                .expirationDate(parsedExpirationDate)
                .defaultSalesProjectionDays(defaultSalesProjectionDays)
                .build();
        quotation = quotationRepository.save(quotation);
        quotationEventRepository.save(QuotationEvent.builder()
                .quotation(quotation)
                .type(QuotationEventType.CREATED)
                .description("Cotação criada via importação de planilha.")
                .performedBy(performedBy)
                .build());

        for (CSVRecord row : rows) {
            String description = row.get(descriptionColumn).trim();
            String barcode = row.get(barcodeColumn).trim();
            String rawQuantity = row.get(quantityColumn).trim();

            if (barcode.isEmpty()) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": código de barras vazio.");
            }
            // Código de barras real sempre tem alguns dígitos (o padrão EAN mais comum
            // no Brasil tem 13) — um valor com 1 ou 2 caracteres quase sempre é sinal de
            // mapeamento errado (coluna de índice/sequência sendo lida como se fosse
            // código de barras), não um código de barras de verdade.
            if (barcode.length() < 3) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": código de barras muito curto (\"" + barcode
                        + "\") — confira se a coluna mapeada como \"Código de Barras\" é mesmo essa.");
            }

            if (description.isEmpty()) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": descrição vazia.");
            }
            // O motivo direto desta checagem: uma planilha malformada (ou mapeamento
            // errado, apontando a coluna de código/índice como se fosse a de descrição)
            // já criou produtos com nome "1", "2", "3"... nesse sistema antes — descrição
            // de produto de verdade sempre tem pelo menos uma letra.
            if (!description.matches(".*[a-zA-ZÀ-ÿ].*")) {
                throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": descrição parece ser só números (\"" + description
                        + "\") — confira se a coluna mapeada como \"Descrição\" não é na verdade a de código de barras ou outra coluna numérica.");
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

            // Célula vazia é sempre válida aqui, mesmo com a coluna mapeada — o preço de
            // custo nunca é obrigatório por linha (ver anotação em QuotationItem), só a
            // decisão de "incluir custo" pra importação inteira é que já foi tomada.
            BigDecimal costPrice = null;
            if (costColumn != null) {
                String rawCost = row.get(costColumn).trim();
                if (!rawCost.isEmpty()) {
                    try {
                        costPrice = new BigDecimal(rawCost.replace(",", "."));
                    } catch (NumberFormatException e) {
                        throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": preço de custo inválido (\"" + rawCost + "\").");
                    }
                    if (costPrice.compareTo(BigDecimal.ZERO) < 0) {
                        throw new BusinessRuleException("Linha " + row.getRecordNumber() + ": preço de custo não pode ser negativo.");
                    }
                }
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
                    .costPrice(costPrice)
                    .build();
            quotationItemRepository.save(item);
        }

        saveOrUpdateProfile(descriptionColumn, barcodeColumn, quantityColumn, costColumn, headerSignature);

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
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("Prazo de expiração inválido: " + expirationDate);
        }
        // Mesma regra da criação manual: prazo é opcional na importação, mas se vier
        // preenchido não pode estar no passado — evita nascer uma cotação já vencida.
        if (parsed.isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("O prazo de expiração não pode estar no passado.");
        }
        return parsed;
    }

    private void validateColumnIndex(Integer column, int headerCount, String fieldLabel) {
        if (column == null || column < 0 || column >= headerCount) {
            throw new BusinessRuleException("Índice de coluna inválido para " + fieldLabel + ".");
        }
    }

    private void saveOrUpdateProfile(Integer descriptionColumn, Integer barcodeColumn, Integer quantityColumn, Integer costColumn, String headerSignature) {
        ImportProfile profile = importProfileRepository.findTopByOrderByUpdatedAtDesc()
                .orElseGet(() -> ImportProfile.builder().build());

        // Só faz sentido preservar a coluna de custo lembrada de uma importação anterior
        // quando é o MESMO layout de planilha — se o cabeçalho mudou, o índice de coluna
        // antigo não tem nenhuma garantia de continuar significando a mesma coisa nessa
        // planilha nova, então melhor esquecer do que reaproveitar errado.
        boolean sameLayout = headerSignature.equals(profile.getHeaderSignature());

        profile.setDescriptionColumn(descriptionColumn);
        profile.setBarcodeColumn(barcodeColumn);
        profile.setQuantityColumn(quantityColumn);
        if (costColumn != null) {
            profile.setCostColumn(costColumn);
        } else if (!sameLayout) {
            profile.setCostColumn(null);
        }
        profile.setHeaderSignature(headerSignature);

        importProfileRepository.save(profile);
    }

    private QuotationResponse toQuotationResponse(Quotation quotation) {
        SupplierGroup group = safeGetSupplierGroup(quotation);
        return new QuotationResponse(
                quotation.getId(),
                quotation.getName(),
                quotation.getStatus(),
                group != null ? group.getId() : null,
                group != null ? group.getName() : null,
                // Importação por planilha nunca cria com fornecedor avulso — só grupo,
                // igual sempre foi. Quem quiser adicionar avulso faz depois, editando a
                // cotação já criada.
                java.util.List.of(),
                java.util.List.of(),
                quotation.getCreatedAt(),
                quotation.getPublishedAt(),
                quotation.getExpirationDate(),
                quotation.getUpdatedAt(),
                quotation.getDefaultSalesProjectionDays(),
                false
        );
    }

    private SupplierGroup safeGetSupplierGroup(Quotation quotation) {
        try {
            return quotation.getSupplierGroup();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }
}