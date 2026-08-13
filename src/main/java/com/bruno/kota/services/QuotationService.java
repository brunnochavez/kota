package com.bruno.kota.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.AddItemWithWinnerRequest;
import com.bruno.kota.dtos.BidResponse;
import com.bruno.kota.dtos.ConfirmCloseRequest;
import com.bruno.kota.dtos.ManualWinnerAssignRequest;
import com.bruno.kota.dtos.MinimumOrderViolation;
import com.bruno.kota.dtos.MinimumOrderViolationItem;
import com.bruno.kota.dtos.QuotationCloseRequest;
import com.bruno.kota.dtos.QuotationCloseResult;
import com.bruno.kota.dtos.QuotationCreateRequest;
import com.bruno.kota.dtos.QuotationFillRate;
import com.bruno.kota.dtos.QuotationItemCreateRequest;
import com.bruno.kota.dtos.QuotationItemResponse;
import com.bruno.kota.dtos.QuotationResponse;
import com.bruno.kota.dtos.QuotationUpdateRequest;
import com.bruno.kota.dtos.ReviewBatchUpdateRequest;
import com.bruno.kota.dtos.TieBreakNeeded;
import com.bruno.kota.entities.Bid;
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
                item.getWinningBid() != null ? item.getWinningBid().getId() : null
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