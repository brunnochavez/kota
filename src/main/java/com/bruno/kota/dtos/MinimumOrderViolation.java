package com.bruno.kota.dtos;
import java.math.BigDecimal;
import java.util.List;

public record MinimumOrderViolation(
        Long supplierId,
        String supplierName,
        BigDecimal total,
        BigDecimal minimumOrderValue,
        List<MinimumOrderViolationItem> wonItems
) {}