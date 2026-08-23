package com.bruno.kota.dtos;

import java.time.LocalDateTime;

import com.bruno.kota.entities.QuotationEventType;

public record QuotationEventResponse(
        Long id,
        QuotationEventType type,
        String description,
        LocalDateTime occurredAt
) {}
