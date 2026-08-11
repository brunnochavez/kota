package com.bruno.kota.dtos;

import java.util.List;

public record QuotationCloseResult(
        boolean closed,
        QuotationResponse quotation,
        List<TieBreakNeeded> pendingTieBreaks,
        List<MinimumOrderViolation> pendingViolations
) {}