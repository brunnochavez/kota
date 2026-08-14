package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.util.List;

// Espelha o espírito de RepresentativePerformance: tudo aqui é calculado em cima de dado
// que o sistema já grava por outro motivo (lances, vencedores, cortes, datas) — nada
// novo é coletado só pra essa tela.
public record AdminInsights(
        BigDecimal totalSavings,
        List<QuotationSaving> topSavingsQuotations,
        List<SupplierShare> supplierConcentration,
        List<LowCompetitionItem> lowCompetitionItems,
        Double averageCycleDays,
        List<SupplierReliabilityRank> reliabilityRanking,
        List<PendingResponse> pendingResponses
) {
    public record QuotationSaving(Long quotationId, String quotationName, BigDecimal saving) {}

    public record SupplierShare(String supplierName, BigDecimal totalValue, double sharePercent) {}

    public record LowCompetitionItem(String quotationName, String productName, String onlySupplierName) {}

    public record SupplierReliabilityRank(String supplierName, int wonItemsTotal, int wonItemsConfirmed, double reliabilityPercent) {}

    public record PendingResponse(Long quotationId, String quotationName, List<String> pendingSupplierNames) {}
}
