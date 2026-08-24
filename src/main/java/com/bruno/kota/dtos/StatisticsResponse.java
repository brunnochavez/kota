package com.bruno.kota.dtos;

import java.util.List;

public record StatisticsResponse(
        List<MonthlyVolumeRow> quotationVolume,
        List<SupplierRankingRow> supplierRanking,
        List<RepresentativeRankingRow> representativeRanking,
        List<MonthlySavingsRow> savingsTrend,
        List<PriceVariationRow> priceVariation
) {}
