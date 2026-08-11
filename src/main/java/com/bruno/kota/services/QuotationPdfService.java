package com.bruno.kota.services;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuotationPdfService {

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;

    @Transactional(readOnly = true)
    public byte[] generateResultPdf(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + quotationId));

        if (quotation.getStatus() != QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Só é possível exportar o resultado de uma cotação já fechada.");
        }

        List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotationId);

        Map<Long, Supplier> suppliersById = new LinkedHashMap<>();
        Map<Long, List<QuotationItem>> itemsBySupplier = new LinkedHashMap<>();

        for (QuotationItem item : items) {
            Bid winner = item.getWinningBid();
            if (winner == null) {
                continue;
            }
            Supplier supplier = winner.getSupplier();
            suppliersById.putIfAbsent(supplier.getId(), supplier);
            itemsBySupplier.computeIfAbsent(supplier.getId(), k -> new ArrayList<>()).add(item);
        }

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Document document = new Document();
            PdfWriter.getInstance(document, outputStream);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 16, Font.BOLD);
            Font supplierFont = new Font(Font.HELVETICA, 13, Font.BOLD);
            Font headerFont = new Font(Font.HELVETICA, 10, Font.BOLD);
            Font cellFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            Font totalFont = new Font(Font.HELVETICA, 11, Font.BOLD);

            document.add(new Paragraph("Resultado da Cotação #" + quotation.getId(), titleFont));
            document.add(new Paragraph(" "));

            for (Map.Entry<Long, List<QuotationItem>> entry : itemsBySupplier.entrySet()) {
                Supplier supplier = suppliersById.get(entry.getKey());
                List<QuotationItem> supplierItems = entry.getValue();

                Paragraph supplierHeading = new Paragraph("Fornecedor: " + supplier.getName(), supplierFont);
                supplierHeading.setSpacingBefore(12);
                document.add(supplierHeading);

                PdfPTable table = new PdfPTable(5);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{2.5f, 4f, 1.5f, 2f, 2f});

                addHeaderCell(table, "Código de Barras", headerFont);
                addHeaderCell(table, "Descrição", headerFont);
                addHeaderCell(table, "Qtd.", headerFont);
                addHeaderCell(table, "Preço Unit.", headerFont);
                addHeaderCell(table, "Subtotal", headerFont);

                BigDecimal supplierTotal = BigDecimal.ZERO;

                for (QuotationItem item : supplierItems) {
                    Bid winner = item.getWinningBid();
                    BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
                    supplierTotal = supplierTotal.add(subtotal);

                    addCell(table, item.getProduct().getBarcode(), cellFont);
                    addCell(table, item.getProduct().getName(), cellFont);
                    addCell(table, item.getQuantity().toPlainString(), cellFont);
                    addCell(table, winner.getValue().toPlainString(), cellFont);
                    addCell(table, subtotal.toPlainString(), cellFont);
                }

                document.add(table);

                Paragraph totalParagraph = new Paragraph("Total do pedido: R$ " + supplierTotal.toPlainString(), totalFont);
                totalParagraph.setAlignment(Element.ALIGN_RIGHT);
                totalParagraph.setSpacingBefore(4);
                document.add(totalParagraph);
            }

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessRuleException("Erro ao gerar o PDF: " + e.getMessage());
        }
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(5);
        table.addCell(cell);
    }
}