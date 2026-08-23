package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record SpendSavingsRow(
        Long supplierGroupId,
        String supplierGroupName,
        BigDecimal totalSavings,
        BigDecimal totalSpend,
        int itemCount
) {}
