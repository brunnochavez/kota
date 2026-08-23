package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.util.List;

public record SpendSavingsSummary(
        BigDecimal totalSavings,
        BigDecimal totalSpend,
        int itemCount,
        List<SpendSavingsRow> byGroup
) {}
