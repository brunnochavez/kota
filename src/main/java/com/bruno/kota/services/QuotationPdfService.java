package com.bruno.kota.services;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // Locale, Color e DateTimeFormatter são todos imutáveis/thread-safe — dá pra
    // deixar como campo estático sem risco. DecimalFormat NÃO é thread-safe (é um
    // problema conhecido do java.text): duas gerações de PDF em paralelo (threads
    // diferentes) compartilhando a mesma instância podem corromper a formatação uma
    // da outra. Por isso os DecimalFormat são criados dentro do método, por chamada,
    // e não como campo estático aqui.
    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Color HEADER_BG = new Color(230, 236, 245);
    private static final Color STRIPE_BG = new Color(246, 248, 251);

    @Transactional(readOnly = true)
    public byte[] generateResultPdf(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + quotationId));

        if (quotation.getStatus() != QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Só é possível exportar o resultado de uma cotação já fechada.");
        }

        DecimalFormat moneyFormat = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        DecimalFormat qtyFormat = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(PT_BR));

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

            Font titleFont = new Font(Font.HELVETICA, 17, Font.BOLD);
            Font subtitleFont = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, Color.GRAY);
            Font supplierFont = new Font(Font.HELVETICA, 13, Font.BOLD);
            Font headerFont = new Font(Font.HELVETICA, 9, Font.BOLD);
            Font cellFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            Font totalFont = new Font(Font.HELVETICA, 11, Font.BOLD);
            Font grandTotalFont = new Font(Font.HELVETICA, 13, Font.BOLD);

            document.add(new Paragraph("Resultado da Cotação #" + quotation.getId() + " — " + quotation.getName(), titleFont));
            Paragraph subtitle = new Paragraph("Gerado em " + LocalDateTime.now().format(DATE_FORMAT), subtitleFont);
            subtitle.setSpacingAfter(14);
            document.add(subtitle);

            BigDecimal grandTotal = BigDecimal.ZERO;

            for (Map.Entry<Long, List<QuotationItem>> entry : itemsBySupplier.entrySet()) {
                Supplier supplier = suppliersById.get(entry.getKey());
                List<QuotationItem> supplierItems = entry.getValue();

                Paragraph supplierHeading = new Paragraph("Fornecedor: " + supplier.getName(), supplierFont);
                supplierHeading.setSpacingBefore(14);
                supplierHeading.setSpacingAfter(6);
                document.add(supplierHeading);

                PdfPTable table = new PdfPTable(5);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{2.3f, 4f, 1.3f, 1.9f, 1.9f});

                addHeaderCell(table, "Código de Barras", headerFont, Element.ALIGN_LEFT);
                addHeaderCell(table, "Descrição", headerFont, Element.ALIGN_LEFT);
                addHeaderCell(table, "Qtd.", headerFont, Element.ALIGN_RIGHT);
                addHeaderCell(table, "Preço Unit. (R$)", headerFont, Element.ALIGN_RIGHT);
                addHeaderCell(table, "Subtotal (R$)", headerFont, Element.ALIGN_RIGHT);

                BigDecimal supplierTotal = BigDecimal.ZERO;
                boolean stripe = false;

                for (QuotationItem item : supplierItems) {
                    Bid winner = item.getWinningBid();
                    BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
                    supplierTotal = supplierTotal.add(subtotal);

                    Color rowColor = stripe ? STRIPE_BG : Color.WHITE;
                    addCell(table, item.getProduct().getBarcode(), cellFont, Element.ALIGN_LEFT, rowColor);
                    addCell(table, item.getProduct().getName(), cellFont, Element.ALIGN_LEFT, rowColor);
                    addCell(table, qtyFormat.format(item.getQuantity()), cellFont, Element.ALIGN_RIGHT, rowColor);
                    addCell(table, moneyFormat.format(winner.getValue()), cellFont, Element.ALIGN_RIGHT, rowColor);
                    addCell(table, moneyFormat.format(subtotal), cellFont, Element.ALIGN_RIGHT, rowColor);
                    stripe = !stripe;
                }

                document.add(table);

                Paragraph totalParagraph = new Paragraph("Total do pedido: R$ " + moneyFormat.format(supplierTotal), totalFont);
                totalParagraph.setAlignment(Element.ALIGN_RIGHT);
                totalParagraph.setSpacingBefore(4);
                document.add(totalParagraph);

                grandTotal = grandTotal.add(supplierTotal);
            }

            // Só faz sentido mostrar um total geral separado quando tem mais de um
            // fornecedor — com um só, seria repetir o mesmo número duas vezes.
            if (itemsBySupplier.size() > 1) {
                Paragraph grandTotalParagraph = new Paragraph("Total geral da cotação: R$ " + moneyFormat.format(grandTotal), grandTotalFont);
                grandTotalParagraph.setAlignment(Element.ALIGN_RIGHT);
                grandTotalParagraph.setSpacingBefore(16);
                document.add(grandTotalParagraph);
            }

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessRuleException("Erro ao gerar o PDF: " + e.getMessage());
        }
    }

    private void addHeaderCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        cell.setBackgroundColor(HEADER_BG);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, Font font, int align, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(5);
        cell.setHorizontalAlignment(align);
        cell.setBackgroundColor(background);
        table.addCell(cell);
    }
}