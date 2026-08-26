package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.bruno.kota.entities.PurchaseOrderStatus;

public record PendingDeliveryItemResponse(
        Long quotationItemId,
        Long quotationId,
        String quotationName,
        Long productId,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Long supplierId,
        String supplierName,
        Long purchaseOrderId,
        LocalDateTime estimatedDeliveryDate,
        PurchaseOrderStatus status
) {}
