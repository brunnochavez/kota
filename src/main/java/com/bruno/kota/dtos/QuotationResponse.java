package com.bruno.kota.dtos;

import java.time.LocalDateTime;

import com.bruno.kota.entities.QuotationStatus;

public record QuotationResponse(
        Long id,
        String name,
        QuotationStatus status,
        Long supplierGroupId,
        String supplierGroupName,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        LocalDateTime expirationDate,
        LocalDateTime updatedAt,
        Integer defaultSalesProjectionDays
) {}