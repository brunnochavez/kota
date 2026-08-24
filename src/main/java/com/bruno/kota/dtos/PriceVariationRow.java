package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record PriceVariationRow(
        Long productId,
        String productName,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal variationPct
) {}
