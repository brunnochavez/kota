package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.bruno.kota.entities.PurchaseOrderStatus;

// Resultado de "Buscar por produto ou fornecedor" → clicar num item: em vez de abrir a
// cotação de origem (que às vezes já nem existe mais ou não é o que interessa), mostra
// o pedido mais recente que ESSE fornecedor específico já ganhou pra ESSE produto
// específico, esteja ele ainda pendente ou já recebido — ver
// PurchaseOrderService.findLastOrderForSupplierAndProduct.
public record LastOrderForProductResponse(
        Long quotationItemId,
        Long quotationId,
        String quotationName,
        Long purchaseOrderId,
        LocalDateTime orderCreatedAt,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Long supplierId,
        String supplierName,
        LocalDateTime estimatedDeliveryDate,
        PurchaseOrderStatus status
) {}
