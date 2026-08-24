package com.bruno.kota.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.MonthlySavingsRow;
import com.bruno.kota.dtos.MonthlyVolumeRow;
import com.bruno.kota.dtos.PriceVariationRow;
import com.bruno.kota.dtos.RepresentativeRankingRow;
import com.bruno.kota.dtos.StatisticsResponse;
import com.bruno.kota.dtos.SupplierRankingRow;
import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationDecline;
import com.bruno.kota.entities.QuotationEligibleSupplier;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.QuotationDeclineRepository;
import com.bruno.kota.repositories.QuotationEligibleSupplierRepository;
import com.bruno.kota.repositories.QuotationRepository;

import lombok.RequiredArgsConstructor;

// Tudo calculado em memória, em cima de 4 consultas (cotações, lances, recusas,
// fotografias de elegibilidade) — sem filtro de período no banco, é o Java que decide a
// janela de tempo depois de já ter os dados na mão. Dataset pequeno o bastante hoje pra
// isso ser tranquilo (mesma decisão já tomada em outros relatórios do sistema, tipo o
// Dashboard de Economia).
//
// Taxa de resposta: usa QuotationEligibleSupplier, a fotografia de quem estava
// elegível tirada no momento em que CADA cotação foi publicada (ver
// QuotationService.snapshotEligibleSuppliers). Isso só existe pra cotações publicadas
// DEPOIS que essa fotografia passou a ser tirada — cotações antigas, publicadas antes
// disso, não entram no cálculo da taxa (não têm fotografia pra comparar), só nas
// contagens brutas (itens ganhos, valor, lances, recusas), que não dependem disso.
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final QuotationRepository quotationRepository;
    private final BidRepository bidRepository;
    private final QuotationDeclineRepository quotationDeclineRepository;
    private final QuotationEligibleSupplierRepository quotationEligibleSupplierRepository;

    private static final int MONTHS_WINDOW = 6;
    private static final int RANKING_LIMIT = 15;
    private static final DateTimeFormatter MONTH_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("MMM/yy", new Locale("pt", "BR"));

    @Transactional(readOnly = true)
    public StatisticsResponse getStatistics() {
        List<Quotation> quotations = quotationRepository.findAll();
        List<Bid> bids = bidRepository.findAllWithFullDetails();
        List<QuotationDecline> declines = quotationDeclineRepository.findAllWithDetails();
        List<QuotationEligibleSupplier> eligibility = quotationEligibleSupplierRepository.findAllWithDetails();

        List<YearMonth> months = lastMonths(MONTHS_WINDOW);

        return new StatisticsResponse(
                buildQuotationVolume(quotations, months),
                buildSupplierRanking(bids, declines, eligibility),
                buildRepresentativeRanking(bids, declines, eligibility),
                buildSavingsTrend(bids, months),
                buildPriceVariation(bids)
        );
    }

    private List<YearMonth> lastMonths(int count) {
        YearMonth current = YearMonth.now();
        List<YearMonth> months = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            months.add(current.minusMonths(i));
        }
        return months;
    }

    private String formatMonth(YearMonth ym) {
        return ym.format(MONTH_LABEL_FORMATTER);
    }

    // "Criadas" conta pela data de criação; "Fechadas" pela data em que a cotação virou
    // CLOSED (updatedAt no momento do fechamento — mesma convenção já usada em Ordem de
    // Compra e no Dashboard de Economia).
    private List<MonthlyVolumeRow> buildQuotationVolume(List<Quotation> quotations, List<YearMonth> months) {
        Map<YearMonth, int[]> counts = new LinkedHashMap<>();
        months.forEach(m -> counts.put(m, new int[2]));

        for (Quotation q : quotations) {
            if (q.getCreatedAt() != null) {
                YearMonth ym = YearMonth.from(q.getCreatedAt());
                int[] bucket = counts.get(ym);
                if (bucket != null) bucket[0]++;
            }
            if (q.getStatus() == QuotationStatus.CLOSED && q.getUpdatedAt() != null) {
                YearMonth ym = YearMonth.from(q.getUpdatedAt());
                int[] bucket = counts.get(ym);
                if (bucket != null) bucket[1]++;
            }
        }

        return months.stream()
                .map(m -> new MonthlyVolumeRow(formatMonth(m), counts.get(m)[0], counts.get(m)[1]))
                .toList();
    }

    // Ordenado por valor total ganho — é o que mais importa pro admin enxergar de
    // relance ("quem eu mais compro"), não simplesmente quem mandou mais lance.
    private List<SupplierRankingRow> buildSupplierRanking(List<Bid> bids, List<QuotationDecline> declines,
                                                            List<QuotationEligibleSupplier> eligibility) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Integer> itemsWon = new HashMap<>();
        Map<Long, BigDecimal> valueWon = new HashMap<>();
        Map<Long, Integer> bidsSubmitted = new HashMap<>();
        Map<Long, Integer> declineCount = new HashMap<>();

        for (Bid b : bids) {
            Long supplierId = b.getSupplier().getId();
            names.putIfAbsent(supplierId, b.getSupplier().getName());
            bidsSubmitted.merge(supplierId, 1, Integer::sum);

            QuotationItem item = b.getQuotationItem();
            Bid winner = item.getWinningBid();
            if (winner != null && winner.getId().equals(b.getId())) {
                itemsWon.merge(supplierId, 1, Integer::sum);
                valueWon.merge(supplierId, b.getValue().multiply(item.getQuantity()), BigDecimal::add);
            }
        }
        for (QuotationDecline d : declines) {
            Long supplierId = d.getSupplier().getId();
            names.putIfAbsent(supplierId, d.getSupplier().getName());
            declineCount.merge(supplierId, 1, Integer::sum);
        }

        // Elegibilidade agrupada por fornecedor → conjunto de ids de cotação em que ele
        // aparecia na fotografia. "Respondeu" = mandou pelo menos 1 lance OU declinou
        // NAQUELA cotação específica — não conta lance por item, é por cotação (uma
        // cotação com 10 itens só conta como "respondida" uma vez, não 10).
        Map<Long, Set<Long>> eligibleQuotationsBySupplier = eligibility.stream()
                .collect(Collectors.groupingBy(e -> e.getSupplier().getId(),
                        Collectors.mapping(e -> e.getQuotation().getId(), Collectors.toSet())));
        Map<Long, Set<Long>> respondedQuotationsBySupplier = new HashMap<>();
        for (Bid b : bids) {
            respondedQuotationsBySupplier
                    .computeIfAbsent(b.getSupplier().getId(), k -> new HashSet<>())
                    .add(b.getQuotationItem().getQuotation().getId());
        }
        for (QuotationDecline d : declines) {
            respondedQuotationsBySupplier
                    .computeIfAbsent(d.getSupplier().getId(), k -> new HashSet<>())
                    .add(d.getQuotation().getId());
        }
        for (QuotationEligibleSupplier e : eligibility) {
            names.putIfAbsent(e.getSupplier().getId(), e.getSupplier().getName());
        }

        return names.keySet().stream()
                .map(id -> {
                    Set<Long> eligibleIds = eligibleQuotationsBySupplier.getOrDefault(id, Set.of());
                    Set<Long> respondedIds = respondedQuotationsBySupplier.getOrDefault(id, Set.of());
                    Double responseRate = eligibleIds.isEmpty() ? null
                            : 100.0 * respondedIds.stream().filter(eligibleIds::contains).count() / eligibleIds.size();
                    return new SupplierRankingRow(
                            id,
                            names.get(id),
                            itemsWon.getOrDefault(id, 0),
                            valueWon.getOrDefault(id, BigDecimal.ZERO),
                            bidsSubmitted.getOrDefault(id, 0),
                            declineCount.getOrDefault(id, 0),
                            responseRate
                    );
                })
                .sorted(Comparator.comparing(SupplierRankingRow::totalValueWon).reversed())
                .limit(RANKING_LIMIT)
                .toList();
    }

    // Mesmo raciocínio do ranking de fornecedores, só que por representante — um
    // representante pode responder por mais de um fornecedor do mesmo grupo, então a
    // elegibilidade dele é a UNIÃO das cotações em que qualquer fornecedor dele
    // aparecia na fotografia.
    private List<RepresentativeRankingRow> buildRepresentativeRanking(List<Bid> bids, List<QuotationDecline> declines,
                                                                        List<QuotationEligibleSupplier> eligibility) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Integer> bidsSubmitted = new HashMap<>();
        Map<Long, Integer> declineCount = new HashMap<>();
        Map<Long, List<Double>> responseHours = new HashMap<>();

        for (Bid b : bids) {
            Long repId = b.getSubmittedBy().getId();
            names.putIfAbsent(repId, b.getSubmittedBy().getName());
            bidsSubmitted.merge(repId, 1, Integer::sum);

            Quotation quotation = b.getQuotationItem().getQuotation();
            if (quotation.getPublishedAt() != null && b.getSubmittedAt() != null
                    && !b.getSubmittedAt().isBefore(quotation.getPublishedAt())) {
                double hours = Duration.between(quotation.getPublishedAt(), b.getSubmittedAt()).toMinutes() / 60.0;
                responseHours.computeIfAbsent(repId, k -> new ArrayList<>()).add(hours);
            }
        }
        for (QuotationDecline d : declines) {
            Long repId = d.getDeclinedBy().getId();
            names.putIfAbsent(repId, d.getDeclinedBy().getName());
            declineCount.merge(repId, 1, Integer::sum);
        }

        Map<Long, Set<Long>> eligibleQuotationsByRep = new HashMap<>();
        for (QuotationEligibleSupplier e : eligibility) {
            Representative rep = e.getRepresentative();
            if (rep == null) continue;
            names.putIfAbsent(rep.getId(), rep.getName());
            eligibleQuotationsByRep.computeIfAbsent(rep.getId(), k -> new HashSet<>()).add(e.getQuotation().getId());
        }
        Map<Long, Set<Long>> respondedQuotationsByRep = new HashMap<>();
        for (Bid b : bids) {
            respondedQuotationsByRep
                    .computeIfAbsent(b.getSubmittedBy().getId(), k -> new HashSet<>())
                    .add(b.getQuotationItem().getQuotation().getId());
        }
        for (QuotationDecline d : declines) {
            respondedQuotationsByRep
                    .computeIfAbsent(d.getDeclinedBy().getId(), k -> new HashSet<>())
                    .add(d.getQuotation().getId());
        }

        return names.keySet().stream()
                .map(id -> {
                    Set<Long> eligibleIds = eligibleQuotationsByRep.getOrDefault(id, Set.of());
                    Set<Long> respondedIds = respondedQuotationsByRep.getOrDefault(id, Set.of());
                    Double responseRate = eligibleIds.isEmpty() ? null
                            : 100.0 * respondedIds.stream().filter(eligibleIds::contains).count() / eligibleIds.size();
                    return new RepresentativeRankingRow(
                            id,
                            names.get(id),
                            bidsSubmitted.getOrDefault(id, 0),
                            declineCount.getOrDefault(id, 0),
                            responseHours.containsKey(id)
                                    ? responseHours.get(id).stream().mapToDouble(Double::doubleValue).average().orElse(0)
                                    : null,
                            responseRate
                    );
                })
                .sorted(Comparator.comparing(RepresentativeRankingRow::bidsSubmitted).reversed())
                .limit(RANKING_LIMIT)
                .toList();
    }

    // Mesmo cálculo do Dashboard de Economia (menor lance vs. média dos lances, só item
    // com vencedor e mais de 1 lance) — só que em vez de um período único, quebrado por
    // mês do fechamento da cotação, pra mostrar tendência.
    private List<MonthlySavingsRow> buildSavingsTrend(List<Bid> bids, List<YearMonth> months) {
        Map<YearMonth, BigDecimal> totals = new LinkedHashMap<>();
        months.forEach(m -> totals.put(m, BigDecimal.ZERO));

        Map<Long, List<Bid>> bidsByItem = bids.stream()
                .collect(Collectors.groupingBy(b -> b.getQuotationItem().getId()));

        for (List<Bid> itemBids : bidsByItem.values()) {
            QuotationItem item = itemBids.get(0).getQuotationItem();
            Quotation quotation = item.getQuotation();
            if (quotation.getStatus() != QuotationStatus.CLOSED || quotation.getUpdatedAt() == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(quotation.getUpdatedAt());
            if (!totals.containsKey(ym)) {
                continue;
            }

            Bid winner = item.getWinningBid();
            if (winner == null || itemBids.size() < 2) {
                continue;
            }

            BigDecimal sum = itemBids.stream().map(Bid::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal average = sum.divide(BigDecimal.valueOf(itemBids.size()), 4, RoundingMode.HALF_UP);
            BigDecimal savings = average.subtract(winner.getValue()).multiply(item.getQuantity()).max(BigDecimal.ZERO);
            totals.merge(ym, savings, BigDecimal::add);
        }

        return months.stream()
                .map(m -> new MonthlySavingsRow(formatMonth(m), totals.get(m)))
                .toList();
    }

    // Só entra produto com pelo menos 2 lances registrados (com 1 só não existe
    // "variação" — seria sempre 0%). variationPct é sobre o menor preço (referência
    // natural: "quanto o mais caro passa do mais barato, em %").
    private List<PriceVariationRow> buildPriceVariation(List<Bid> bids) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, BigDecimal> min = new HashMap<>();
        Map<Long, BigDecimal> max = new HashMap<>();
        Map<Long, Integer> count = new HashMap<>();

        for (Bid b : bids) {
            Product product = b.getQuotationItem().getProduct();
            Long productId = product.getId();
            names.putIfAbsent(productId, product.getName());
            count.merge(productId, 1, Integer::sum);
            min.merge(productId, b.getValue(), BigDecimal::min);
            max.merge(productId, b.getValue(), BigDecimal::max);
        }

        return names.keySet().stream()
                .filter(id -> count.getOrDefault(id, 0) >= 2)
                .map(id -> {
                    BigDecimal minPrice = min.get(id);
                    BigDecimal maxPrice = max.get(id);
                    BigDecimal variationPct = minPrice.compareTo(BigDecimal.ZERO) > 0
                            ? maxPrice.subtract(minPrice).divide(minPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO;
                    return new PriceVariationRow(id, names.get(id), minPrice, maxPrice, variationPct);
                })
                .sorted(Comparator.comparing(PriceVariationRow::variationPct).reversed())
                .limit(RANKING_LIMIT)
                .toList();
    }
}
