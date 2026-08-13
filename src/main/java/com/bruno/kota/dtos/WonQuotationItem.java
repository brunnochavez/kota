package com.bruno.kota.dtos;
import java.math.BigDecimal;

public record WonQuotationItem(
        Long quotationItemId,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        BigDecimal value,
        BigDecimal subtotal
) {}
