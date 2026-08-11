package com.bruno.kota.dtos;

import java.util.List;
import java.util.Map;

public record QuotationCloseRequest(
        Map<Long, Long> tieBreakWinners,
        List<Long> excludedSupplierIds,
        List<Long> acceptedViolationSupplierIds
) {}