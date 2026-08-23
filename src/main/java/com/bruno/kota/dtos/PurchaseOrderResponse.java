package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.bruno.kota.entities.PurchaseOrderStatus;

public record PurchaseOrderResponse(
        Long id,
        Long quotationId,
        String quotationName,
        Long supplierId,
        String supplierName,
        LocalDateTime createdAt,
        LocalDateTime estimatedDeliveryDate,
        BigDecimal totalValue,
        PurchaseOrderStatus status,
        LocalDateTime receivedAt
) {}
