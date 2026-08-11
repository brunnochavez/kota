package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BidResponse(
        Long id,
        Long quotationItemId,
        Long supplierId,
        String supplierName,
        Long submittedById,
        String submittedByName,
        BigDecimal value,
        Integer deliveryDeadlineDays,
        String notes,
        LocalDateTime submittedAt,
        LocalDateTime updatedAt
) {}