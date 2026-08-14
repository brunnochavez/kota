package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.util.List;

// Todo indicador aqui é calculado em cima de dado que o sistema já grava pra outro fim —
// nada novo é coletado só pra essa tela. decidedBids conta só lances em itens que já têm
// vencedor definido (winningBid != null); enquanto a cotação ainda tá em aberto, não dá
// pra saber se foi vitória ou derrota, então esses lances ficam de fora da taxa.
public record RepresentativePerformance(
        int decidedBids,
        int wonBids,
        Double winRate,
        BigDecimal totalWonValue,
        BigDecimal averageWonQuotationValue,
        int wonItemsTotal,
        int wonItemsConfirmed,
        Double fulfillmentReliability,
        Integer eligibleQuotationsCount,
        Integer respondedQuotationsCount,
        Double participationRate,
        List<ProductWinRate> byProduct,
        List<RecentLoss> recentLosses
) {
    public record ProductWinRate(
            String productName,
            int bids,
            int wins,
            double winRate
    ) {}

    public record RecentLoss(
            String quotationName,
            String productName,
            BigDecimal yourValue,
            BigDecimal winningValue,
            BigDecimal gap
    ) {}
}
