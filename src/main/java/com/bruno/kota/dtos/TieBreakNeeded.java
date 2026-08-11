package com.bruno.kota.dtos;

import java.util.List;

public record TieBreakNeeded(
        Long quotationItemId,
        String productName,
        List<BidResponse> tiedBids
) {}