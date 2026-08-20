package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record QuotationItemResponse(
        Long id,
        Long quotationId,
        Long productId,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        Long winningBidId,
        boolean fulfillmentCut,
        Integer salesProjectionDaysOverride,
        Integer effectiveSalesProjectionDays
) {}