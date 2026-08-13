package com.bruno.kota.dtos;

// eligibleCount/filledCount aqui já são só dessa cotação — ver comentário em
// QuotationService.getRepresentativeFillRate() pra critério completo de elegibilidade.
public record QuotationFillRate(
        Long quotationId,
        int eligibleCount,
        int filledCount
) {}
