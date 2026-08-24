package com.bruno.kota.controllers;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.PendingDeliveryItemResponse;
import com.bruno.kota.dtos.PurchaseOrderResponse;
import com.bruno.kota.services.PurchaseOrderService;
import com.bruno.kota.services.QuotationPdfService;

import lombok.RequiredArgsConstructor;

// Toda ordem de compra é gerada automaticamente por PurchaseOrderService quando uma
// cotação é confirmada como fechada — não existe criação manual aqui, só consulta e a
// ação de marcar como recebida. Só admin acessa (não tem visão de OC pro representante
// ainda; ele já vê o pedido dele em "Resultado da cotação" / "Ganhei").
@RestController
@RequestMapping("/purchase-orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final QuotationPdfService quotationPdfService;

    @GetMapping
    public List<PurchaseOrderResponse> findAll() {
        return purchaseOrderService.findAll();
    }

    @PostMapping("/{id}/mark-received")
    public PurchaseOrderResponse markReceived(@PathVariable Long id) {
        return purchaseOrderService.markReceived(id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        byte[] pdf = quotationPdfService.generatePurchaseOrderPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ordem-compra-" + id + ".pdf")
                .body(pdf);
    }

    // "Produtos a Receber" — mesmos dados das Ordens de Compra, só que quebrados por
    // item em vez de por fornecedor, e já filtrados pra só trazer quem ainda não passou
    // do prazo de entrega (ver PurchaseOrderService.findPendingDeliveryItems).
    @GetMapping("/pending-deliveries")
    public List<PendingDeliveryItemResponse> findPendingDeliveries() {
        return purchaseOrderService.findPendingDeliveryItems();
    }
}
