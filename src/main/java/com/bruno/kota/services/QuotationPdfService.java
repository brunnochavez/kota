package com.bruno.kota.services;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.openpdf.text.Image;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.CompanySettingsResponse;
import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.PurchaseOrder;
import com.bruno.kota.entities.PurchaseOrderStatus;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.PurchaseOrderRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuotationPdfService {

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final CompanySettingsService companySettingsService;
    private final PurchaseOrderRepository purchaseOrderRepository;

    // Locale, Color e DateTimeFormatter são todos imutáveis/thread-safe — dá pra
    // deixar como campo estático sem risco. DecimalFormat NÃO é thread-safe (é um
    // problema conhecido do java.text): duas gerações de PDF em paralelo (threads
    // diferentes) compartilhando a mesma instância podem corromper a formatação uma
    // da outra. Por isso os DecimalFormat são criados dentro do método, por chamada,
    // e não como campo estático aqui.
    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Paleta alinhada com a cor de destaque usada no próprio site (--accent: #2f6fed),
    // pra o PDF parecer parte do mesmo produto, não um relatório genérico de planilha.
    private static final Color ACCENT = new Color(47, 111, 237);
    private static final Color ACCENT_DARK = new Color(30, 58, 138);
    private static final Color ACCENT_SOFT = new Color(232, 240, 254);
    private static final Color TEXT_DIM = new Color(107, 114, 128);
    private static final Color BORDER_LIGHT = new Color(228, 231, 236);
    private static final Color STRIPE_BG = new Color(248, 249, 251);

    // A logo do Kota (marca do software) é um arquivo estático do próprio projeto —
    // igual pra todo mundo, diferente da logo da empresa-cliente (essa sim varia por
    // tenant, vem de CompanySettings). Por ser sempre a mesma, carrega uma vez só e
    // reaproveita — não faz sentido reler o arquivo do disco a cada PDF gerado.
    private static volatile byte[] kotaWordmarkBytes;

    private static byte[] loadKotaWordmark() {
        if (kotaWordmarkBytes == null) {
            synchronized (QuotationPdfService.class) {
                if (kotaWordmarkBytes == null) {
                    try (InputStream in = new ClassPathResource("static/img/kota-wordmark.png").getInputStream()) {
                        kotaWordmarkBytes = in.readAllBytes();
                    } catch (IOException e) {
                        kotaWordmarkBytes = new byte[0];
                    }
                }
            }
        }
        return kotaWordmarkBytes;
    }

    // Rodapé de marca — "Gerado com Kota" + número da página, em toda página do
    // documento (não só na última). O PdfPageEventHelper é o jeito certo de fazer isso
    // no OpenPDF: desenha direto no content stream de cada página conforme ela é
    // fechada, então funciona igual não importa quantas páginas o PDF tiver.
    private static class KotaFooterEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float pageWidth = document.getPageSize().getWidth();
            float bottom = document.bottom() - 24;

            cb.setColorStroke(BORDER_LIGHT);
            cb.setLineWidth(0.75f);
            cb.moveTo(document.leftMargin(), bottom + 14);
            cb.lineTo(pageWidth - document.rightMargin(), bottom + 14);
            cb.stroke();

            byte[] logoBytes = loadKotaWordmark();
            if (logoBytes.length > 0) {
                try {
                    Image logo = Image.getInstance(logoBytes);
                    logo.scaleToFit(52, 15);
                    logo.setAbsolutePosition(document.leftMargin(), bottom - 2);
                    cb.addImage(logo);
                } catch (Exception e) {
                    // Sem logo, sem drama — o rodapé segue sem ela.
                }
            }

            Font pageNumFont = new Font(Font.HELVETICA, 8, Font.NORMAL, TEXT_DIM);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Página " + writer.getPageNumber(), pageNumFont),
                    pageWidth - document.rightMargin(), bottom, 0);
        }
    }

    // Reaproveitado pelos dois PDFs — logo (se tiver sido cadastrada) mais nome/CNPJ/
    // endereço/telefone da empresa, sempre no topo do documento. Se não tiver nada
    // configurado ainda (empresa nova, ninguém preencheu os dados), simplesmente não
    // adiciona nada — o PDF continua funcionando normalmente, só sem esse cabeçalho.
    private void addCompanyHeader(Document document) throws DocumentException {
        CompanySettingsResponse company = companySettingsService.get();
        byte[] logoBytes = companySettingsService.readLogoBytes();

        if (logoBytes != null) {
            try {
                Image logo = Image.getInstance(logoBytes);
                logo.scaleToFit(90, 50);
                logo.setAlignment(Element.ALIGN_LEFT);
                document.add(logo);
            } catch (Exception e) {
                // Logo corrompida ou formato que o OpenPDF não reconhece — não trava a
                // geração do PDF por causa disso, só segue sem a imagem.
            }
        }

        Font companyNameFont = new Font(Font.HELVETICA, 12, Font.BOLD);
        Font companyDetailFont = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, TEXT_DIM);

        if (company.name() != null && !company.name().isBlank()) {
            document.add(new Paragraph(company.name(), companyNameFont));
        }

        // Linha 1: identificação fiscal. Linha 2: endereço completo, já remontado a
        // partir dos campos separados. Linha 3: contato. Cada uma só aparece se tiver
        // pelo menos um dado preenchido — não deixa "CNPJ: · IE: " com separador solto.
        String fiscalLine = joinNonBlank(" · ",
                labelOrNull("CNPJ: ", company.cnpj()),
                labelOrNull("IE: ", company.stateRegistration()));

        String addressLine = joinNonBlank(", ",
                company.address(),
                company.neighborhood());
        String cityStateLine = joinNonBlank(" - ", company.city(), company.state());
        String fullAddress = joinNonBlank(" · ", addressLine, cityStateLine,
                labelOrNull("CEP ", company.zipCode()));

        String contactLine = joinNonBlank(" · ", company.phone(), company.email());

        for (String line : new String[]{fiscalLine, fullAddress, contactLine}) {
            if (line != null && !line.isBlank()) {
                document.add(new Paragraph(line, companyDetailFont));
            }
        }
        Paragraph spacer = new Paragraph(" ", companyDetailFont);
        spacer.setSpacingAfter(4);
        document.add(spacer);
    }

    // Faixa colorida com o título — em vez de um Paragraph preto solto no topo, como
    // documento de Word/Excel padrão. Uma tabela de 1 célula só é o jeito mais simples
    // e confiável de conseguir um fundo colorido atrás de texto no OpenPDF.
    private void addTitleBand(Document document, String title, String subtitle) throws DocumentException {
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(ACCENT_DARK);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(14);

        Font titleFont = new Font(Font.HELVETICA, 16, Font.BOLD, Color.WHITE);
        Font subtitleFont = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(203, 213, 225));

        Paragraph titleP = new Paragraph(title, titleFont);
        Paragraph subtitleP = new Paragraph(subtitle, subtitleFont);
        subtitleP.setSpacingBefore(3);
        cell.addElement(titleP);
        cell.addElement(subtitleP);

        band.addCell(cell);
        band.setSpacingAfter(16);
        document.add(band);
    }

    // Título do bloco de cada fornecedor — uma faixa clara com barra de destaque à
    // esquerda, em vez de só um texto em negrito. Ajuda a separar visualmente um
    // fornecedor do outro quando o PDF tem vários (rola bastante quando um pedido é
    // dividido entre fornecedores diferentes).
    private void addSupplierHeading(Document document, String supplierName) throws DocumentException {
        PdfPTable heading = new PdfPTable(1);
        heading.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell(new Phrase(supplierName, new Font(Font.HELVETICA, 12.5f, Font.BOLD, ACCENT_DARK)));
        cell.setBackgroundColor(ACCENT_SOFT);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderColor(ACCENT);
        cell.setBorderWidthLeft(3.5f);
        cell.setPadding(9);
        heading.addCell(cell);
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(8);
        document.add(heading);
    }

    private PdfPTable buildItemsTable(List<QuotationItem> items, DecimalFormat moneyFormat, DecimalFormat qtyFormat) {
        Font headerFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
        Font cellFont = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, new Color(31, 41, 55));

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{2.3f, 4f, 1.3f, 1.9f, 1.9f});
        } catch (DocumentException e) {
            // Não acontece de verdade (array já tem o mesmo tamanho de colunas), mas o
            // método declara a exceção — sem isso o compilador reclama.
        }

        addHeaderCell(table, "Código de Barras", headerFont, Element.ALIGN_LEFT);
        addHeaderCell(table, "Descrição", headerFont, Element.ALIGN_LEFT);
        addHeaderCell(table, "Qtd.", headerFont, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Preço Unit. (R$)", headerFont, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Subtotal (R$)", headerFont, Element.ALIGN_RIGHT);

        boolean stripe = false;
        for (QuotationItem item : items) {
            Bid winner = item.getWinningBid();
            BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
            Color rowColor = stripe ? STRIPE_BG : Color.WHITE;
            addCell(table, item.getProduct().getBarcode(), cellFont, Element.ALIGN_LEFT, rowColor);
            addCell(table, item.getProduct().getName(), cellFont, Element.ALIGN_LEFT, rowColor);
            addCell(table, qtyFormat.format(item.getQuantity()), cellFont, Element.ALIGN_RIGHT, rowColor);
            addCell(table, moneyFormat.format(winner.getValue()), cellFont, Element.ALIGN_RIGHT, rowColor);
            addCell(table, moneyFormat.format(subtotal), cellFont, Element.ALIGN_RIGHT, rowColor);
            stripe = !stripe;
        }
        return table;
    }

    // Total como um cartão destacado (fundo suave + borda), não só um texto alinhado à
    // direita perdido no meio da página — é o número mais importante do documento,
    // merece se destacar visualmente do resto.
    private void addTotalCallout(Document document, String label, BigDecimal value, DecimalFormat moneyFormat, boolean emphasized) throws DocumentException {
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(46);
        wrap.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell cell = new PdfPCell(new Phrase(label + "  R$ " + moneyFormat.format(value),
                new Font(Font.HELVETICA, emphasized ? 12.5f : 11f, Font.BOLD, emphasized ? Color.WHITE : ACCENT_DARK)));
        cell.setBackgroundColor(emphasized ? ACCENT : ACCENT_SOFT);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(10);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        wrap.addCell(cell);
        wrap.setSpacingBefore(emphasized ? 14 : 6);
        document.add(wrap);
    }

    private String labelOrNull(String label, String value) {
        return (value != null && !value.isBlank()) ? label + value : null;
    }

    private String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) sb.append(separator);
                sb.append(part);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

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
            Document document = new Document(org.openpdf.text.PageSize.A4, 36, 36, 36, 54);
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);
            writer.setPageEvent(new KotaFooterEvent());
            document.open();
            addCompanyHeader(document);
            addTitleBand(document, "Cotação #" + quotation.getId() + " — " + quotation.getName(),
                    "Resultado gerado em " + LocalDateTime.now().format(DATE_FORMAT));

            BigDecimal grandTotal = BigDecimal.ZERO;

            for (Map.Entry<Long, List<QuotationItem>> entry : itemsBySupplier.entrySet()) {
                Supplier supplier = suppliersById.get(entry.getKey());
                List<QuotationItem> supplierItems = entry.getValue();

                addSupplierHeading(document, supplier.getName());
                document.add(buildItemsTable(supplierItems, moneyFormat, qtyFormat));

                BigDecimal supplierTotal = supplierItems.stream()
                        .map(item -> item.getWinningBid().getValue().multiply(item.getQuantity()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                addTotalCallout(document, "Total do pedido:", supplierTotal, moneyFormat, false);
                grandTotal = grandTotal.add(supplierTotal);
            }

            // Só faz sentido mostrar um total geral separado quando tem mais de um
            // fornecedor — com um só, seria repetir o mesmo número duas vezes.
            if (itemsBySupplier.size() > 1) {
                addTotalCallout(document, "Total geral da cotação:", grandTotal, moneyFormat, true);
            }

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessRuleException("Erro ao gerar o PDF: " + e.getMessage());
        }
    }

    // Mesmo desenho do PDF geral, só que filtrado pra UM fornecedor — é o que o
    // representante baixa do lado dele (não faz sentido ele ver o pedido dos outros
    // fornecedores). Por já ser um fornecedor só, não tem "total geral" separado — o
    // total do pedido já É o total geral aqui.
    public byte[] generateSupplierResultPdf(Long quotationId, Long supplierId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + quotationId));

        if (quotation.getStatus() != QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Só é possível exportar o resultado de uma cotação já fechada.");
        }

        DecimalFormat moneyFormat = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        DecimalFormat qtyFormat = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(PT_BR));

        List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotationId);
        List<QuotationItem> supplierItems = new ArrayList<>();
        Supplier supplier = null;

        for (QuotationItem item : items) {
            Bid winner = item.getWinningBid();
            if (winner == null || !winner.getSupplier().getId().equals(supplierId) || item.isFulfillmentCut()) {
                continue;
            }
            supplier = winner.getSupplier();
            supplierItems.add(item);
        }

        if (supplier == null) {
            throw new BusinessRuleException("Nenhum item ganho por esse fornecedor nesta cotação.");
        }

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Document document = new Document(org.openpdf.text.PageSize.A4, 36, 36, 36, 54);
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);
            writer.setPageEvent(new KotaFooterEvent());
            document.open();
            addCompanyHeader(document);
            addTitleBand(document, "Pedido — Cotação #" + quotation.getId() + " — " + quotation.getName(),
                    "Gerado em " + LocalDateTime.now().format(DATE_FORMAT));

            addSupplierHeading(document, "Fornecedor: " + supplier.getName());
            document.add(buildItemsTable(supplierItems, moneyFormat, qtyFormat));

            BigDecimal supplierTotal = supplierItems.stream()
                    .map(item -> item.getWinningBid().getValue().multiply(item.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            addTotalCallout(document, "Total do pedido:", supplierTotal, moneyFormat, true);

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessRuleException("Erro ao gerar o PDF: " + e.getMessage());
        }
    }

    // Documento formal da Ordem de Compra — diferente do PDF de resultado (que é sobre
    // a cotação como um todo), esse é sobre UM pedido específico já fechado: número
    // próprio, dados fiscais da empresa, previsão de entrega e status de recebimento.
    // Reaproveita os mesmos helpers visuais (cabeçalho, faixa de título, tabela de
    // itens, total) — mesma identidade visual do PDF de resultado, documento diferente.
    @Transactional(readOnly = true)
    public byte[] generatePurchaseOrderPdf(Long purchaseOrderId) {
        PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Ordem de compra não encontrada: id " + purchaseOrderId));

        Quotation quotation = order.getQuotation();
        Supplier supplier = order.getSupplier();

        DecimalFormat moneyFormat = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        DecimalFormat qtyFormat = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(PT_BR));

        // Todos os itens ganhos por esse fornecedor nessa cotação — ignora
        // fulfillmentCut de propósito: a OC registra o que foi PEDIDO originalmente,
        // não o estado atual de atendimento (isso já é papel do PDF de resultado e da
        // tela "Resultado da cotação" do representante).
        List<QuotationItem> supplierItems = new ArrayList<>();
        for (QuotationItem item : quotationItemRepository.findByQuotationId(quotation.getId())) {
            Bid winner = item.getWinningBid();
            if (winner != null && winner.getSupplier().getId().equals(supplier.getId())) {
                supplierItems.add(item);
            }
        }

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Document document = new Document(org.openpdf.text.PageSize.A4, 36, 36, 36, 54);
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);
            writer.setPageEvent(new KotaFooterEvent());
            document.open();
            addCompanyHeader(document);
            addTitleBand(document, "Ordem de Compra Nº " + formatOrderNumber(order.getId()),
                    "Gerada em " + order.getCreatedAt().format(DATE_FORMAT));

            Font labelFont = new Font(Font.HELVETICA, 9.5f, Font.BOLD, new Color(31, 41, 55));
            Font valueFont = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, TEXT_DIM);

            addInfoLine(document, "Cotação de origem: ", "#" + quotation.getId() + " — " + quotation.getName(), labelFont, valueFont);
            addInfoLine(document, "Previsão de entrega: ",
                    order.getEstimatedDeliveryDate() != null ? order.getEstimatedDeliveryDate().format(DATE_FORMAT) : "Não informada",
                    labelFont, valueFont);
            addInfoLine(document, "Status: ",
                    order.getStatus() == PurchaseOrderStatus.RECEIVED
                            ? "Recebida em " + order.getReceivedAt().format(DATE_FORMAT)
                            : "Pendente de recebimento",
                    labelFont, valueFont);

            addSupplierHeading(document, "Fornecedor: " + supplier.getName());
            document.add(buildItemsTable(supplierItems, moneyFormat, qtyFormat));

            BigDecimal total = supplierItems.stream()
                    .map(item -> item.getWinningBid().getValue().multiply(item.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            addTotalCallout(document, "Total da ordem de compra:", total, moneyFormat, true);

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessRuleException("Erro ao gerar o PDF: " + e.getMessage());
        }
    }

    // Mesmo padrão do número de cotação exibido no frontend (formatQuotationNumber) —
    // zero à esquerda até 6 dígitos, visual de documento formal.
    private String formatOrderNumber(Long id) {
        return String.format("%06d", id);
    }

    private void addInfoLine(Document document, String label, String value, Font labelFont, Font valueFont) throws DocumentException {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label, labelFont));
        p.add(new Phrase(value, valueFont));
        p.setSpacingAfter(3);
        document.add(p);
    }

    private void addHeaderCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(7);
        cell.setHorizontalAlignment(align);
        cell.setBackgroundColor(ACCENT);
        cell.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, Font font, int align, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        cell.setBackgroundColor(background);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(BORDER_LIGHT);
        cell.setBorderWidthBottom(0.75f);
        table.addCell(cell);
    }
}
