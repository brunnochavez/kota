package com.bruno.kota.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.bruno.kota.dtos.*;
import com.bruno.kota.dtos.AdminInsights;
import com.bruno.kota.dtos.RepresentativePerformance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.OrderFulfillmentConfirmation;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.OrderFulfillmentConfirmationRepository;
import com.bruno.kota.repositories.ProductRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.SupplierGroupRepository;
import com.bruno.kota.repositories.SupplierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final ProductRepository productRepository;
    private final SupplierGroupRepository supplierGroupRepository;
    private final SupplierRepository supplierRepository;
    private final RepresentativeRepository representativeRepository;
    private final BidRepository bidRepository;
    private final OrderFulfillmentConfirmationRepository orderFulfillmentConfirmationRepository;

    @Transactional(readOnly = true)
    public List<QuotationResponse> findAll() {
        return quotationRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public QuotationResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public List<QuotationItemResponse> findItems(Long quotationId) {
        findEntityById(quotationId);
        return quotationItemRepository.findByQuotationId(quotationId).stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional
    public QuotationResponse createManually(QuotationCreateRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessRuleException("Uma cotação precisa de pelo menos um item.");
        }

        Quotation quotation = Quotation.builder()
                .name(request.name())
                .supplierGroup(resolveSupplierGroup(request.supplierGroupId()))
                .expirationDate(request.expirationDate())
                .build();
        quotation = quotationRepository.save(quotation);

        for (QuotationItemCreateRequest itemRequest : request.items()) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: id " + itemRequest.productId()));

            QuotationItem item = QuotationItem.builder()
                    .quotation(quotation)
                    .product(product)
                    .quantity(itemRequest.quantity())
                    .build();
            quotationItemRepository.save(item);
        }

        return toResponse(quotation);
    }

    @Transactional
    public QuotationResponse update(Long id, QuotationUpdateRequest request) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() == QuotationStatus.CLOSED
                || quotation.getStatus() == QuotationStatus.EXPIRED
                || quotation.getStatus() == QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Não é possível editar nome/grupo/prazo durante a revisão, fechamento ou expiração.");
        }

        if (quotation.getStatus() == QuotationStatus.AVAILABLE
                && request.expirationDate() != null
                && quotation.getExpirationDate() != null
                && request.expirationDate().isBefore(quotation.getExpirationDate())) {
            throw new BusinessRuleException("Não é permitido encurtar o prazo de uma cotação já publicada.");
        }

        quotation.setName(request.name());
        quotation.setSupplierGroup(resolveSupplierGroup(request.supplierGroupId()));
        quotation.setExpirationDate(request.expirationDate());
        return toResponse(quotationRepository.save(quotation));
    }

    @Transactional
    public QuotationResponse publish(Long id) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new BusinessRuleException("Só é possível publicar uma cotação que esteja em DRAFT.");
        }
        if (quotation.getSupplierGroup() == null) {
            throw new BusinessRuleException("Defina o grupo de fornecedores antes de publicar.");
        }
        if (quotation.getExpirationDate() == null) {
            throw new BusinessRuleException("Defina o prazo de expiração antes de publicar.");
        }

        quotation.setStatus(QuotationStatus.AVAILABLE);
        quotation.setPublishedAt(LocalDateTime.now());
        return toResponse(quotationRepository.save(quotation));
    }

    @Transactional
    public QuotationCloseResult close(Long id, QuotationCloseRequest request) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.AVAILABLE && quotation.getStatus() != QuotationStatus.EXPIRED) {
            throw new BusinessRuleException("Só é possível fechar uma cotação que esteja disponível ou expirada.");
        }

        Set<Long> excludedSupplierIds = request.excludedSupplierIds() != null
                ? new HashSet<>(request.excludedSupplierIds()) : Set.of();
        Set<Long> acceptedViolationSupplierIds = request.acceptedViolationSupplierIds() != null
                ? new HashSet<>(request.acceptedViolationSupplierIds()) : Set.of();
        Map<Long, Long> tieBreakWinners = request.tieBreakWinners() != null
                ? request.tieBreakWinners() : Map.of();

        List<QuotationItem> items = quotationItemRepository.findByQuotationId(id);

        Map<Long, Bid> itemWinners = new HashMap<>();
        Map<Long, Bid> itemRunnersUp = new HashMap<>();
        List<TieBreakNeeded> pendingTies = new ArrayList<>();

        for (QuotationItem item : items) {
            List<Bid> eligibleBids = bidRepository.findByQuotationItemId(item.getId()).stream()
                    .filter(bid -> !excludedSupplierIds.contains(bid.getSupplier().getId()))
                    .toList();

            if (eligibleBids.isEmpty()) {
                itemWinners.put(item.getId(), null);
                continue;
            }

            BigDecimal minValue = eligibleBids.stream()
                    .map(Bid::getValue)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();

            // Segunda melhor oferta (valor distinto acima do menor) — guardada aqui, e não
            // recalculada depois, pra usar como sugestão no card de pedido mínimo abaixo:
            // "se excluir esse fornecedor, esse aqui assume o item, por esse preço".
            eligibleBids.stream()
                    .filter(bid -> bid.getValue().compareTo(minValue) > 0)
                    .min(Comparator.comparing(Bid::getValue))
                    .ifPresent(runnerUp -> itemRunnersUp.put(item.getId(), runnerUp));

            List<Bid> tiedBids = eligibleBids.stream()
                    .filter(bid -> bid.getValue().compareTo(minValue) == 0)
                    .toList();

            if (tiedBids.size() == 1) {
                itemWinners.put(item.getId(), tiedBids.get(0));
                continue;
            }

            Long chosenBidId = tieBreakWinners.get(item.getId());
            Bid chosen = tiedBids.stream()
                    .filter(bid -> bid.getId().equals(chosenBidId))
                    .findFirst()
                    .orElse(null);

            if (chosen != null) {
                itemWinners.put(item.getId(), chosen);
            } else {
                pendingTies.add(new TieBreakNeeded(
                        item.getId(),
                        item.getProduct().getName(),
                        tiedBids.stream().map(this::toBidResponse).toList()
                ));
            }
        }

        if (!pendingTies.isEmpty()) {
            return new QuotationCloseResult(false, toResponse(quotation), pendingTies, List.of());
        }

        List<MinimumOrderViolation> violations = buildMinimumOrderViolations(
                items, itemWinners, itemRunnersUp, acceptedViolationSupplierIds);

        if (!violations.isEmpty()) {
            return new QuotationCloseResult(false, toResponse(quotation), List.of(), violations);
        }

        for (QuotationItem item : items) {
            item.setWinningBid(itemWinners.get(item.getId()));
            quotationItemRepository.save(item);
        }

        quotation.setStatus(QuotationStatus.REVIEWING);
        quotationRepository.save(quotation);

        return new QuotationCloseResult(true, toResponse(quotation), List.of(), List.of());
    }

    // Compartilhado entre close() (vencedores recém-calculados) e confirmClose() (vencedores
    // já definidos, revalidados no estado atual). Junta o total ganho por fornecedor e
    // compara com o pedido mínimo; ignora quem já está em acceptedViolationSupplierIds.
    private List<MinimumOrderViolation> buildMinimumOrderViolations(
            List<QuotationItem> items,
            Map<Long, Bid> itemWinners,
            Map<Long, Bid> itemRunnersUp,
            Set<Long> acceptedViolationSupplierIds) {

        Map<Long, BigDecimal> supplierTotals = new HashMap<>();
        Map<Long, Supplier> suppliersById = new HashMap<>();
        Map<Long, List<QuotationItem>> supplierItemsById = new HashMap<>();

        for (QuotationItem item : items) {
            Bid winner = itemWinners.get(item.getId());
            if (winner == null) {
                continue;
            }
            Supplier supplier = winner.getSupplier();
            BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
            supplierTotals.merge(supplier.getId(), subtotal, BigDecimal::add);
            suppliersById.putIfAbsent(supplier.getId(), supplier);
            supplierItemsById.computeIfAbsent(supplier.getId(), k -> new ArrayList<>()).add(item);
        }

        List<MinimumOrderViolation> violations = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : supplierTotals.entrySet()) {
            Long supplierId = entry.getKey();
            BigDecimal total = entry.getValue();
            Supplier supplier = suppliersById.get(supplierId);

            if (supplier.getMinimumOrderValue() != null
                    && total.compareTo(supplier.getMinimumOrderValue()) < 0
                    && !acceptedViolationSupplierIds.contains(supplierId)) {
                List<MinimumOrderViolationItem> violationItems = supplierItemsById.get(supplierId).stream()
                        .map(item -> {
                            Bid winner = itemWinners.get(item.getId());
                            Bid runnerUp = itemRunnersUp.get(item.getId());
                            return new MinimumOrderViolationItem(
                                    item.getId(),
                                    item.getProduct().getName(),
                                    item.getQuantity(),
                                    winner.getValue(),
                                    runnerUp != null ? runnerUp.getValue() : null,
                                    runnerUp != null ? runnerUp.getSupplier().getName() : null
                            );
                        })
                        .toList();
                violations.add(new MinimumOrderViolation(
                        supplierId, supplier.getName(), total, supplier.getMinimumOrderValue(), violationItems
                ));
            }
        }

        return violations;
    }

    // Revalida o pedido mínimo por fornecedor no estado ATUAL dos itens — não só bloqueia
    // no valor errado. O close() só checa isso na hora de calcular os vencedores; depois
    // disso, ajustes individuais por representante (mudar quantidade/preço, excluir um
    // lance) não invalidam mais o resto da revisão (de propósito, ver
    // applyReviewBatchUpdate), então também não voltam a passar por essa checagem. Sem
    // isso aqui, dava pra reduzir o pedido de um fornecedor pra baixo do mínimo depois do
    // close() e confirmar o fechamento assim mesmo, gerando PDF com pedido que nunca
    // deveria ter sido aceito.
    //
    // "aceitar mesmo assim" do close() não fica guardado em lugar nenhum (é só um
    // parâmetro daquela chamada) — se essa violação for detectada de novo aqui, o admin
    // precisa aceitar de novo, mesmo que já tenha aceitado no close(). Reconfirmar antes
    // de virar CLOSED de vez (não dá mais pra editar depois) é intencional, não sobra de
    // implementação.
    @Transactional
    public QuotationCloseResult confirmClose(Long id, ConfirmCloseRequest request) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Só é possível confirmar o fechamento de uma cotação em revisão.");
        }

        Set<Long> acceptedViolationSupplierIds = request != null && request.acceptedViolationSupplierIds() != null
                ? new HashSet<>(request.acceptedViolationSupplierIds()) : Set.of();

        List<QuotationItem> items = quotationItemRepository.findByQuotationId(id);

        Map<Long, Bid> itemWinners = new HashMap<>();
        Map<Long, Bid> itemRunnersUp = new HashMap<>();

        for (QuotationItem item : items) {
            Bid winner = item.getWinningBid();
            itemWinners.put(item.getId(), winner);
            if (winner == null) {
                continue;
            }
            // "Segunda opção" aqui não é recalculada do zero (os vencedores já estão
            // definidos, não tem empate/exclusão rolando) — é só o melhor lance elegível
            // que não é o vencedor atual, pra sugerir uma alternativa no card.
            bidRepository.findByQuotationItemId(item.getId()).stream()
                    .filter(bid -> !bid.getId().equals(winner.getId()))
                    .min(Comparator.comparing(Bid::getValue))
                    .ifPresent(runnerUp -> itemRunnersUp.put(item.getId(), runnerUp));
        }

        List<MinimumOrderViolation> violations = buildMinimumOrderViolations(
                items, itemWinners, itemRunnersUp, acceptedViolationSupplierIds);

        if (!violations.isEmpty()) {
            return new QuotationCloseResult(false, toResponse(quotation), List.of(), violations);
        }

        quotation.setStatus(QuotationStatus.CLOSED);
        return new QuotationCloseResult(true, toResponse(quotationRepository.save(quotation)), List.of(), List.of());
    }

    @Transactional
    public QuotationItemResponse assignManualWinner(Long quotationId, Long itemId, ManualWinnerAssignRequest request) {
        Quotation quotation = findEntityById(quotationId);

        if (quotation.getStatus() != QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Só é possível atribuir um vencedor manualmente durante a revisão.");
        }

        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));

        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
        }

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + request.supplierId()));
        Representative representative = representativeRepository.findById(request.representativeId())
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + request.representativeId()));

        Bid bid = bidRepository.findByQuotationItemIdAndSupplierId(itemId, supplier.getId())
                .orElseGet(() -> Bid.builder().quotationItem(item).supplier(supplier).build());
        bid.setSubmittedBy(representative);
        bid.setValue(request.value());
        bid.setDeliveryDeadlineDays(request.deliveryDeadlineDays());
        bid = bidRepository.save(bid);

        item.setWinningBid(bid);
        quotationItemRepository.save(item);

        return toItemResponse(item);
    }

    @Transactional
    public QuotationItemResponse addItemWithWinner(Long quotationId, AddItemWithWinnerRequest request) {
        Quotation quotation = findEntityById(quotationId);

        if (quotation.getStatus() != QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Só é possível adicionar um item com vencedor durante a revisão.");
        }

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: id " + request.productId()));

        QuotationItem item = QuotationItem.builder()
                .quotation(quotation)
                .product(product)
                .quantity(request.quantity())
                .build();
        item = quotationItemRepository.save(item);

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + request.supplierId()));
        Representative representative = representativeRepository.findById(request.representativeId())
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + request.representativeId()));

        Bid bid = Bid.builder()
                .quotationItem(item)
                .supplier(supplier)
                .submittedBy(representative)
                .value(request.value())
                .deliveryDeadlineDays(request.deliveryDeadlineDays())
                .build();
        bid = bidRepository.save(bid);

        item.setWinningBid(bid);
        item = quotationItemRepository.save(item);

        return toItemResponse(item);
    }

    // Salva um lote de mudanças (preço de lances + quantidade de itens) numa transação única.
    //
    // Não invalida mais a revisão no final. No frontend, a tabela geral de itens (DRAFT)
    // e o ajuste fino por representante (REVIEWING) usam esse mesmo endpoint, mas depois
    // que a tabela geral virou somente-leitura durante REVIEWING, esse método só roda com
    // a cotação em REVIEWING quando vem do ajuste fino por representante — mexendo só na
    // quantidade/preço do vencedor JÁ calculado daquele item, sem trocar quem venceu. Por
    // isso não precisa (e não deve) desfazer o cálculo dos OUTROS itens: é uma correção
    // pontual, no mesmo espírito de assignManualWinner/addItemWithWinner, que também não
    // invalidam. Pra DRAFT isso não muda nada — invalidateReviewIfNeeded já não fazia nada
    // fora de REVIEWING.
    @Transactional
    public void applyReviewBatchUpdate(Long quotationId, ReviewBatchUpdateRequest request) {
        Quotation quotation = findEntityById(quotationId);

        if (quotation.getStatus() != QuotationStatus.DRAFT && quotation.getStatus() != QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Só é possível editar itens em Rascunho ou durante a revisão antes de confirmar o fechamento.");
        }

        if (request.bidUpdates() != null) {
            request.bidUpdates().stream()
                    .sorted(Comparator.comparing(ReviewBatchUpdateRequest.BidPriceUpdateItem::bidId))
                    .forEach(upd -> {
                        Bid bid = bidRepository.findById(upd.bidId())
                                .orElseThrow(() -> new ResourceNotFoundException("Lance não encontrado: id " + upd.bidId()));
                        bid.setValue(upd.value());
                        bid.setDeliveryDeadlineDays(upd.deliveryDeadlineDays());
                        bidRepository.save(bid);
                    });
        }

        if (request.itemUpdates() != null) {
            request.itemUpdates().stream()
                    .sorted(Comparator.comparing(ReviewBatchUpdateRequest.ItemQuantityUpdateItem::itemId))
                    .forEach(upd -> {
                        QuotationItem item = quotationItemRepository.findById(upd.itemId())
                                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + upd.itemId()));
                        if (!item.getQuotation().getId().equals(quotationId)) {
                            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
                        }
                        item.setQuantity(upd.quantity());
                        quotationItemRepository.save(item);
                    });
        }
    }

    @Transactional
    public QuotationItemResponse addItem(Long quotationId, QuotationItemCreateRequest request) {
        Quotation quotation = findEntityById(quotationId);
        ensureEditable(quotation);
        invalidateReviewIfNeeded(quotation);

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: id " + request.productId()));

        QuotationItem item = QuotationItem.builder()
                .quotation(quotation)
                .product(product)
                .quantity(request.quantity())
                .build();
        return toItemResponse(quotationItemRepository.save(item));
    }

    @Transactional
    public QuotationItemResponse updateItemQuantity(Long quotationId, Long itemId, BigDecimal quantity) {
        Quotation quotation = findEntityById(quotationId);
        ensureEditable(quotation);
        invalidateReviewIfNeeded(quotation);

        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));

        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
        }

        item.setQuantity(quantity);
        return toItemResponse(quotationItemRepository.save(item));
    }

    @Transactional
    public void removeItem(Long quotationId, Long itemId) {
        Quotation quotation = findEntityById(quotationId);
        ensureEditable(quotation);
        invalidateReviewIfNeeded(quotation);

        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));

        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
        }

        quotationItemRepository.delete(item);
    }

    private void ensureEditable(Quotation quotation) {
        if (quotation.getStatus() != QuotationStatus.DRAFT && quotation.getStatus() != QuotationStatus.REVIEWING) {
            throw new BusinessRuleException("Só é possível editar itens em Rascunho ou durante a revisão antes de confirmar o fechamento.");
        }
    }

    private void invalidateReviewIfNeeded(Quotation quotation) {
        if (quotation.getStatus() != QuotationStatus.REVIEWING) {
            return;
        }
        List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotation.getId());
        for (QuotationItem item : items) {
            item.setWinningBid(null);
            quotationItemRepository.save(item);
        }
        quotation.setStatus(QuotationStatus.AVAILABLE);
        quotationRepository.save(quotation);
    }

    // Só considera cotações AVAILABLE — é a janela em que representantes ainda podem
    // responder. "Elegível" = representante dono de um fornecedor que pertence ao grupo
    // daquela cotação. Cotações sem grupo definido ou sem nenhum representante elegível
    // ficam fora da lista — não tem barra pra mostrar.
    @Transactional(readOnly = true)
    public List<QuotationFillRate> getRepresentativeFillRate() {
        List<Quotation> available = quotationRepository.findByStatus(QuotationStatus.AVAILABLE);
        List<QuotationFillRate> rates = new ArrayList<>();

        for (Quotation quotation : available) {
            SupplierGroup group = quotation.getSupplierGroup();
            if (group == null) {
                continue;
            }

            Set<Long> eligibleRepIds = supplierRepository.findByGroup(group).stream()
                    .map(Supplier::getRepresentative)
                    .filter(Objects::nonNull)
                    .map(Representative::getId)
                    .collect(Collectors.toSet());

            if (eligibleRepIds.isEmpty()) {
                continue;
            }

            Set<Long> submittedRepIds = bidRepository.findByQuotationItem_QuotationId(quotation.getId()).stream()
                    .map(bid -> bid.getSubmittedBy().getId())
                    .collect(Collectors.toSet());

            int filled = (int) eligibleRepIds.stream().filter(submittedRepIds::contains).count();
            rates.add(new QuotationFillRate(quotation.getId(), eligibleRepIds.size(), filled));
        }

        return rates;
    }

    // Cotações fechadas onde esse fornecedor ganhou pelo menos um item — usado na tela do
    // representante ("O que eu ganhei"). Só entra cotação CLOSED (Em Revisão ainda pode
    // mudar, não faz sentido mostrar como resultado definitivo ainda). Cada representante
    // só vê os PRÓPRIOS itens ganhos, nunca os de outro fornecedor — diferente do PDF
    // (que é do admin e mostra todo mundo), essa lista aqui é sempre por fornecedor.
    //
    // Só mostra depois de finalizado (ver finalizeFulfillment) — antes disso, os mesmos
    // itens aparecem em findPendingFulfillmentResults, não aqui.
    @Transactional(readOnly = true)
    public List<WonQuotationSummary> findWonQuotations(Long supplierId) {
        return findFulfillmentSummaries(supplierId, true);
    }

    // Espelho de findWonQuotations, mas pro que ainda NÃO foi finalizado — é o que
    // alimenta "Resultados de Cotações" na tela do representante, onde ele confirma ou
    // corta cada item antes de finalizar o pedido.
    @Transactional(readOnly = true)
    public List<WonQuotationSummary> findPendingFulfillmentResults(Long supplierId) {
        return findFulfillmentSummaries(supplierId, false);
    }

    private List<WonQuotationSummary> findFulfillmentSummaries(Long supplierId, boolean finalized) {
        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);
        List<WonQuotationSummary> result = new ArrayList<>();

        for (Quotation quotation : closedQuotations) {
            boolean isFinalized = orderFulfillmentConfirmationRepository
                    .existsByQuotationIdAndSupplierId(quotation.getId(), supplierId);
            if (isFinalized != finalized) {
                continue;
            }

            List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotation.getId());
            List<WonQuotationItem> wonItems = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;

            for (QuotationItem item : items) {
                Bid winner = item.getWinningBid();
                if (winner == null || !winner.getSupplier().getId().equals(supplierId) || item.isFulfillmentCut()) {
                    continue;
                }
                BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
                total = total.add(subtotal);
                wonItems.add(new WonQuotationItem(
                        item.getId(),
                        item.getProduct().getName(),
                        item.getProduct().getBarcode(),
                        item.getQuantity(),
                        winner.getValue(),
                        subtotal
                ));
            }

            if (!wonItems.isEmpty()) {
                result.add(new WonQuotationSummary(quotation.getId(), quotation.getName(), quotation.getUpdatedAt(), wonItems, total));
            }
        }

        result.sort((a, b) -> {
            if (a.closedAt() == null || b.closedAt() == null) return 0;
            return b.closedAt().compareTo(a.closedAt());
        });

        return result;
    }

    // Marca "não tenho isso em estoque" — some da relação pro representante (não conta
    // mais em findFulfillmentSummaries, nem pendente nem finalizado) e fica registrado
    // pro admin ver o que foi cortado. Só antes de finalizar: depois disso o pedido tá
    // fechado de vez, igual não dá pra editar quantidade de cotação já CLOSED.
    // Todo indicador aqui já usava dado gravado por outro motivo — lance, vencedor, corte
    // de estoque. Só um lance em item já DECIDIDO (winningBid != null) entra na taxa de
    // vitória; enquanto a cotação ainda tá em aberto, não dá pra julgar se foi vitória ou
    // derrota. Participação usa o grupo ATUAL do fornecedor pra achar cotações elegíveis
    // — se o grupo mudou depois de alguma cotação antiga, isso é aproximado, não um
    // retrato histórico exato (o sistema não guarda "grupo em que estava em cada data").
    @Transactional(readOnly = true)
    public RepresentativePerformance getRepresentativePerformance(Long supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        List<Bid> myBids = bidRepository.findBySupplierId(supplierId);

        int decidedBids = 0;
        int wonBids = 0;
        int wonItemsTotal = 0;
        int wonItemsConfirmed = 0;
        BigDecimal totalWonValue = BigDecimal.ZERO;
        Map<Long, BigDecimal> valueByQuotation = new HashMap<>();
        Map<String, int[]> byProductStats = new LinkedHashMap<>();
        List<RepresentativePerformance.RecentLoss> losses = new ArrayList<>();

        List<Bid> sortedBids = myBids.stream()
                .sorted(Comparator.comparing(Bid::getSubmittedAt).reversed())
                .toList();

        for (Bid bid : sortedBids) {
            QuotationItem item = bid.getQuotationItem();
            Bid winner = item.getWinningBid();
            if (winner == null) {
                continue;
            }

            String productName = item.getProduct().getName();
            decidedBids++;
            int[] stats = byProductStats.computeIfAbsent(productName, k -> new int[2]);
            stats[0]++;

            if (winner.getId().equals(bid.getId())) {
                wonBids++;
                stats[1]++;
                wonItemsTotal++;
                if (!item.isFulfillmentCut()) {
                    wonItemsConfirmed++;
                }

                BigDecimal subtotal = bid.getValue().multiply(item.getQuantity());
                totalWonValue = totalWonValue.add(subtotal);
                valueByQuotation.merge(item.getQuotation().getId(), subtotal, BigDecimal::add);
            } else if (losses.size() < 5) {
                losses.add(new RepresentativePerformance.RecentLoss(
                        item.getQuotation().getName(),
                        productName,
                        bid.getValue(),
                        winner.getValue(),
                        bid.getValue().subtract(winner.getValue())
                ));
            }
        }

        Double winRate = decidedBids > 0 ? (wonBids * 100.0 / decidedBids) : null;
        Double fulfillmentReliability = wonItemsTotal > 0 ? (wonItemsConfirmed * 100.0 / wonItemsTotal) : null;
        BigDecimal averageWonQuotationValue = valueByQuotation.isEmpty()
                ? null
                : totalWonValue.divide(BigDecimal.valueOf(valueByQuotation.size()), 2, RoundingMode.HALF_UP);

        List<RepresentativePerformance.ProductWinRate> byProduct = byProductStats.entrySet().stream()
                .map(e -> new RepresentativePerformance.ProductWinRate(
                        e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] > 0 ? e.getValue()[1] * 100.0 / e.getValue()[0] : 0
                ))
                .sorted((a, b) -> Integer.compare(b.bids(), a.bids()))
                .toList();

        Set<Long> myGroupIds = supplier.getGroups().stream().map(SupplierGroup::getId).collect(Collectors.toSet());
        List<Quotation> published = quotationRepository.findAll().stream()
                .filter(q -> q.getStatus() != QuotationStatus.DRAFT)
                .filter(q -> q.getSupplierGroup() != null && myGroupIds.contains(q.getSupplierGroup().getId()))
                .toList();

        Set<Long> respondedQuotationIds = myBids.stream()
                .map(b -> b.getQuotationItem().getQuotation().getId())
                .collect(Collectors.toSet());

        int eligibleCount = published.size();
        int respondedCount = (int) published.stream().filter(q -> respondedQuotationIds.contains(q.getId())).count();
        Double participationRate = eligibleCount > 0 ? (respondedCount * 100.0 / eligibleCount) : null;

        return new RepresentativePerformance(
                decidedBids, wonBids, winRate, totalWonValue, averageWonQuotationValue,
                wonItemsTotal, wonItemsConfirmed, fulfillmentReliability,
                eligibleCount, respondedCount, participationRate,
                byProduct, losses
        );
    }

    // Visão do lado do admin, espelhando o espírito de getRepresentativePerformance — só
    // que agregado entre TODOS os fornecedores, não um só. Economia compara o preço
    // vencedor com o MAIOR lance recebido pra cada item (o que teria custado sem
    // concorrência); baixa concorrência e "quem não respondeu" olham só pro que ainda é
    // acionável agora (Disponível), o resto olha pro histórico (Fechada).
    @Transactional(readOnly = true)
    public AdminInsights getAdminInsights() {
        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);

        BigDecimal totalSavings = BigDecimal.ZERO;
        List<AdminInsights.QuotationSaving> savingsByQuotation = new ArrayList<>();
        Map<String, BigDecimal> valueBySupplier = new LinkedHashMap<>();
        List<AdminInsights.LowCompetitionItem> lowCompetition = new ArrayList<>();
        Map<String, int[]> reliabilityBySupplier = new LinkedHashMap<>();
        long totalCycleHours = 0;
        int cycleCount = 0;

        for (Quotation quotation : closedQuotations) {
            List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotation.getId());
            BigDecimal quotationSaving = BigDecimal.ZERO;

            for (QuotationItem item : items) {
                Bid winner = item.getWinningBid();
                if (winner == null) {
                    continue;
                }

                List<Bid> allBids = bidRepository.findByQuotationItemId(item.getId());
                BigDecimal maxBid = allBids.stream().map(Bid::getValue).max(Comparator.naturalOrder()).orElse(winner.getValue());
                BigDecimal itemSaving = maxBid.subtract(winner.getValue()).multiply(item.getQuantity());
                if (itemSaving.compareTo(BigDecimal.ZERO) > 0) {
                    quotationSaving = quotationSaving.add(itemSaving);
                }

                if (allBids.size() == 1) {
                    lowCompetition.add(new AdminInsights.LowCompetitionItem(
                            quotation.getName(), item.getProduct().getName(), winner.getSupplier().getName()
                    ));
                }

                String supplierName = winner.getSupplier().getName();
                BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
                valueBySupplier.merge(supplierName, subtotal, BigDecimal::add);

                int[] stats = reliabilityBySupplier.computeIfAbsent(supplierName, k -> new int[2]);
                stats[0]++;
                if (!item.isFulfillmentCut()) {
                    stats[1]++;
                }
            }

            totalSavings = totalSavings.add(quotationSaving);
            if (quotationSaving.compareTo(BigDecimal.ZERO) > 0) {
                savingsByQuotation.add(new AdminInsights.QuotationSaving(quotation.getId(), quotation.getName(), quotationSaving));
            }

            if (quotation.getPublishedAt() != null && quotation.getUpdatedAt() != null) {
                totalCycleHours += Duration.between(quotation.getPublishedAt(), quotation.getUpdatedAt()).toHours();
                cycleCount++;
            }
        }

        List<AdminInsights.QuotationSaving> topSavings = savingsByQuotation.stream()
                .sorted((a, b) -> b.saving().compareTo(a.saving()))
                .limit(5)
                .toList();

        BigDecimal totalValue = valueBySupplier.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AdminInsights.SupplierShare> concentration = valueBySupplier.entrySet().stream()
                .map(e -> new AdminInsights.SupplierShare(
                        e.getKey(),
                        e.getValue(),
                        totalValue.compareTo(BigDecimal.ZERO) > 0
                                ? e.getValue().multiply(BigDecimal.valueOf(100)).divide(totalValue, 1, RoundingMode.HALF_UP).doubleValue()
                                : 0
                ))
                .sorted((a, b) -> Double.compare(b.sharePercent(), a.sharePercent()))
                .limit(5)
                .toList();

        // Pior primeiro — é o que precisa de atenção, não o que já está bem.
        List<AdminInsights.SupplierReliabilityRank> reliability = reliabilityBySupplier.entrySet().stream()
                .map(e -> new AdminInsights.SupplierReliabilityRank(
                        e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] > 0 ? e.getValue()[1] * 100.0 / e.getValue()[0] : 0
                ))
                .sorted(Comparator.comparingDouble(AdminInsights.SupplierReliabilityRank::reliabilityPercent))
                .toList();

        Double averageCycleDays = cycleCount > 0 ? (totalCycleHours / 24.0) / cycleCount : null;

        List<Quotation> available = quotationRepository.findByStatus(QuotationStatus.AVAILABLE);
        List<AdminInsights.PendingResponse> pending = new ArrayList<>();
        for (Quotation quotation : available) {
            SupplierGroup group = quotation.getSupplierGroup();
            if (group == null) {
                continue;
            }
            List<Supplier> eligible = supplierRepository.findByGroup(group);
            Set<Long> respondedSupplierIds = bidRepository.findByQuotationItem_QuotationId(quotation.getId()).stream()
                    .map(b -> b.getSupplier().getId())
                    .collect(Collectors.toSet());
            List<String> missing = eligible.stream()
                    .filter(s -> !respondedSupplierIds.contains(s.getId()))
                    .map(Supplier::getName)
                    .toList();
            if (!missing.isEmpty()) {
                pending.add(new AdminInsights.PendingResponse(quotation.getId(), quotation.getName(), missing));
            }
        }

        return new AdminInsights(
                totalSavings, topSavings, concentration,
                lowCompetition.stream().limit(10).toList(),
                averageCycleDays, reliability, pending
        );
    }

    // Marca "não tenho isso em estoque" — some da relação pro representante (não conta
    // mais em findFulfillmentSummaries, nem pendente nem finalizado) e fica registrado
    // pro admin ver o que foi cortado. Só antes de finalizar: depois disso o pedido tá
    // fechado de vez, igual não dá pra editar quantidade de cotação já CLOSED.
    @Transactional
    public void cutFulfillmentItem(Long quotationId, Long itemId) {
        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));

        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
        }
        if (item.getQuotation().getStatus() != QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Só é possível registrar corte de estoque em itens de cotação já fechada.");
        }
        if (item.getWinningBid() == null) {
            throw new BusinessRuleException("Esse item não tem vencedor definido.");
        }

        Long supplierId = item.getWinningBid().getSupplier().getId();
        if (orderFulfillmentConfirmationRepository.existsByQuotationIdAndSupplierId(item.getQuotation().getId(), supplierId)) {
            throw new BusinessRuleException("Esse pedido já foi finalizado — não é mais possível cortar itens.");
        }

        item.setFulfillmentCut(true);
        quotationItemRepository.save(item);
    }

    // Idempotente de propósito — clicar em "Finalizar Pedido" duas vezes (duplo toque,
    // rede lenta) não deve dar erro, só não faz nada na segunda vez.
    @Transactional
    public void finalizeFulfillment(Long quotationId, Long supplierId) {
        Quotation quotation = findEntityById(quotationId);

        if (quotation.getStatus() != QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Só é possível finalizar o pedido de uma cotação já fechada.");
        }

        if (orderFulfillmentConfirmationRepository.existsByQuotationIdAndSupplierId(quotationId, supplierId)) {
            return;
        }

        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        orderFulfillmentConfirmationRepository.save(
                OrderFulfillmentConfirmation.builder().quotation(quotation).supplier(supplier).build()
        );
    }

    private SupplierGroup resolveSupplierGroup(Long supplierGroupId) {
        if (supplierGroupId == null) {
            return null;
        }
        return supplierGroupRepository.findById(supplierGroupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + supplierGroupId));
    }

    private Quotation findEntityById(Long id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + id));
    }

    private QuotationResponse toResponse(Quotation quotation) {
        SupplierGroup group = quotation.getSupplierGroup();
        return new QuotationResponse(
                quotation.getId(),
                quotation.getName(),
                quotation.getStatus(),
                group != null ? group.getId() : null,
                group != null ? group.getName() : null,
                quotation.getCreatedAt(),
                quotation.getPublishedAt(),
                quotation.getExpirationDate(),
                quotation.getUpdatedAt()
        );
    }

    private QuotationItemResponse toItemResponse(QuotationItem item) {
        return new QuotationItemResponse(
                item.getId(),
                item.getQuotation().getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getBarcode(),
                item.getQuantity(),
                item.getWinningBid() != null ? item.getWinningBid().getId() : null,
                item.isFulfillmentCut()
        );
    }

    private BidResponse toBidResponse(Bid bid) {
        return new BidResponse(
                bid.getId(),
                bid.getQuotationItem().getId(),
                bid.getSupplier().getId(),
                bid.getSupplier().getName(),
                bid.getSubmittedBy().getId(),
                bid.getSubmittedBy().getName(),
                bid.getValue(),
                bid.getDeliveryDeadlineDays(),
                bid.getNotes(),
                bid.getSubmittedAt(),
                bid.getUpdatedAt()
        );
    }
}