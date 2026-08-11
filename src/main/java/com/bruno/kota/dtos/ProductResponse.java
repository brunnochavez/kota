package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String barcode,
        String name,
        String description,
        String unitOfMeasure,
        BigDecimal lastQuotedPrice,
        String lastQuotedSupplierName
) {}