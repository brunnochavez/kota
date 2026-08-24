package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record SupplierRankingRow(
        Long supplierId,
        String supplierName,
        int itemsWon,
        BigDecimal totalValueWon,
        int bidsSubmitted,
        int declines,
        Double responseRatePct
) {}
