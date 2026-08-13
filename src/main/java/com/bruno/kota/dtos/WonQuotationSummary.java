package com.bruno.kota.dtos;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record WonQuotationSummary(
        Long quotationId,
        String quotationName,
        LocalDateTime closedAt,
        List<WonQuotationItem> items,
        BigDecimal total
) {}
