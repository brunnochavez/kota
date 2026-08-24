package com.bruno.kota.dtos;

public record RepresentativeRankingRow(
        Long representativeId,
        String representativeName,
        int bidsSubmitted,
        int declines,
        Double avgResponseHours,
        Double responseRatePct
) {}
