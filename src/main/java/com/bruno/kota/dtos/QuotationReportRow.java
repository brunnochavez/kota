package com.bruno.kota.dtos;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record QuotationReportRow(
        Long quotationId,
        String quotationName,
        LocalDateTime closedAt,
        String supplierName,
        String representativeName,
        String productName,
        BigDecimal quantity,
        BigDecimal unitValue,
        BigDecimal subtotal,
        boolean won,
        boolean orderConfirmed
) {}
