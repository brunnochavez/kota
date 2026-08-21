package com.bruno.kota.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.bruno.kota.dtos.RepresentativePerformance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.OrderFulfillmentConfirmation;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationDecline;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.OrderFulfillmentConfirmationRepository;
import com.bruno.kota.repositories.QuotationDeclineRepository;
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
    private final QuotationDeclineRepository quotationDeclineRepository;

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
                .defaultSalesProjectionDays(request.defaultSalesProjectionDays())
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

    // Clona nome/grupo/projeção padrão/itens (produto + quantidade) pra uma nova
    // cotação em DRAFT — sem prazo de expiração (o admin define de novo) e sem nada
    // de vencedor/corte/sobrescrita de projeção por item, já que é uma cotação nova,
    // não uma continuação da antiga. Pensado principalmente pra cotação EXPIRED que o
    // admin quer refazer do zero sem redigitar produto por produto, mas funciona a
    // partir de qualquer status — o "gerador" é sempre a lista de itens atual.
    @Transactional
    public QuotationResponse duplicate(Long id) {
        Quotation original = findEntityById(id);
        List<QuotationItem> originalItems = quotationItemRepository.findByQuotationId(id);
        return duplicateInternal(original, originalItems, " (cópia)");
    }

    // Mesma ideia do duplicate() normal, mas só clona os itens que fecharam SEM
    // vencedor (ninguém ofertou) — o caso de uso é gerar rapidinho uma cotação nova só
    // com o que sobrou pra tentar de novo com outros fornecedores/representantes, sem
    // arrastar junto os itens que já foram bem atendidos na cotação original.
    @Transactional
    public QuotationResponse duplicateUnquotedItems(Long id) {
        Quotation original = findEntityById(id);
        List<QuotationItem> unquotedItems = quotationItemRepository.findByQuotationId(id).stream()
                .filter(item -> item.getWinningBid() == null)
                .toList();
        if (unquotedItems.isEmpty()) {
            throw new BusinessRuleException("Não há itens sem lance nessa cotação.");
        }
        return duplicateInternal(original, unquotedItems, " (sem cotação)");
    }

    private QuotationResponse duplicateInternal(Quotation original, List<QuotationItem> itemsToClone, String nameSuffix) {
        Quotation copy = Quotation.builder()
                .name(original.getName() + nameSuffix)
                .supplierGroup(original.getSupplierGroup())
                .defaultSalesProjectionDays(original.getDefaultSalesProjectionDays())
                .build();
        copy = quotationRepository.save(copy);

        for (QuotationItem item : itemsToClone) {
            QuotationItem newItem = QuotationItem.builder()
                    .quotation(copy)
                    .product(item.getProduct())
                    .quantity(item.getQuantity())
                    .build();
            quotationItemRepository.save(newItem);
        }

        return toResponse(copy);
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
        quotation.setDefaultSalesProjectionDays(request.defaultSalesProjectionDays());
        return toResponse(quotationRepository.save(quotation));
    }

    // Exclusão DE VERDADE, só permitida em Rascunho — a partir de Disponível, já foi
    // publicada e representantes já podem ter visto/respondido; nesse ponto "excluir"
    // deixaria de ser seguro (perderia participação real). Cotação nunca tem @SQLDelete
    // (não existe conceito de "desativar" cotação), então isso já é um DELETE de verdade
    // desde sempre — só precisa apagar os itens primeiro por causa da FK.
    @Transactional
    public void delete(Long id) {
        Quotation quotation = findEntityById(id);
        if (quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new BusinessRuleException(
                    "Só é possível excluir cotações em Rascunho — publicada, ela já foi disponibilizada para representantes.");
        }
        quotationItemRepository.deleteAll(quotationItemRepository.findByQuotationId(id));
        quotationRepository.delete(quotation);
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

    // Sem ensureEditable/invalidateReviewIfNeeded de propósito: projeção de venda é só
    // anotação de planejamento do admin (quanto tempo ele acha que vai levar pra vender
    // esse produto), não interfere em lance, vencedor ou fechamento — então pode ser
    // ajustada a qualquer momento, mesmo com a cotação já fechada.
    @Transactional
    public QuotationItemResponse updateItemSalesProjection(Long quotationId, Long itemId, Integer salesProjectionDays) {
        findEntityById(quotationId);

        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));

        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new BusinessRuleException("Esse item não pertence a essa cotação.");
        }
        if (salesProjectionDays != null && salesProjectionDays <= 0) {
            throw new BusinessRuleException("Projeção de venda deve ser maior que zero.");
        }

        item.setSalesProjectionDaysOverride(salesProjectionDays);
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

            // "Não Cotar" conta como resposta pra taxa de participação — o representante
            // olhou a cotação e declarou explicitamente que não tem nada a oferecer, o
            // que é bem diferente de simplesmente nunca ter aberto a cotação.
            Set<Long> declinedRepIds = quotationDeclineRepository.findByQuotationId(quotation.getId()).stream()
                    .map(d -> d.getDeclinedBy().getId())
                    .collect(Collectors.toSet());

            Set<Long> respondedRepIds = new HashSet<>(submittedRepIds);
            respondedRepIds.addAll(declinedRepIds);

            int filled = (int) eligibleRepIds.stream().filter(respondedRepIds::contains).count();
            rates.add(new QuotationFillRate(quotation.getId(), eligibleRepIds.size(), filled));
        }

        return rates;
    }

    // Detalhe por trás do "X/Y responderam" — quem exatamente já respondeu (e como) e
    // quem ainda falta. Diferente do fill rate acima, funciona pra qualquer status (não
    // só AVAILABLE): depois de fechada, ainda é útil ver quem participou. PENDING vem
    // primeiro na ordenação — é a informação mais acionável pro admin (quem cutucar).
    @Transactional(readOnly = true)
    public List<RepresentativeResponseStatus> getRepresentativeResponseStatus(Long quotationId) {
        Quotation quotation = findEntityById(quotationId);
        SupplierGroup group = quotation.getSupplierGroup();
        if (group == null) {
            return List.of();
        }

        Set<Long> submittedRepIds = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                .map(bid -> bid.getSubmittedBy().getId())
                .collect(Collectors.toSet());
        Set<Long> declinedRepIds = quotationDeclineRepository.findByQuotationId(quotationId).stream()
                .map(d -> d.getDeclinedBy().getId())
                .collect(Collectors.toSet());

        List<RepresentativeResponseStatus> result = new ArrayList<>();
        for (Supplier supplier : supplierRepository.findByGroup(group)) {
            Representative rep = supplier.getRepresentative();
            if (rep == null) {
                continue;
            }
            String status = submittedRepIds.contains(rep.getId()) ? "SUBMITTED"
                    : declinedRepIds.contains(rep.getId()) ? "DECLINED"
                    : "PENDING";
            result.add(new RepresentativeResponseStatus(rep.getId(), rep.getName(), supplier.getName(), status));
        }

        Map<String, Integer> statusOrder = Map.of("PENDING", 0, "DECLINED", 1, "SUBMITTED", 2);
        result.sort(Comparator.<RepresentativeResponseStatus>comparingInt(r -> statusOrder.get(r.status()))
                .thenComparing(RepresentativeResponseStatus::representativeName));
        return result;
    }

    // Mesmo princípio do BidService.submit(): authenticatedRepresentativeId vem do token,
    // não de nada que o cliente declare. null = quem chamou é admin, sem restrição —
    // admin já tem acesso a qualquer fornecedor. Pra representante, barra na hora se o
    // supplierId pedido não for de uma empresa que ele de fato representa — sem isso,
    // um representante logado podia trocar o supplierId na URL e ver/agir em nome de
    // qualquer outro fornecedor do sistema.
    private void validateSupplierOwnership(Long supplierId, Long authenticatedRepresentativeId) {
        if (authenticatedRepresentativeId == null) {
            return;
        }
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));
        boolean owns = supplier.getRepresentative() != null
                && supplier.getRepresentative().getId().equals(authenticatedRepresentativeId);
        if (!owns) {
            throw new BusinessRuleException("Esse representante não está autorizado a agir em nome desse fornecedor.");
        }
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
    public List<WonQuotationSummary> findWonQuotations(Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        return findFulfillmentSummaries(supplierId, true);
    }

    // Espelho de findWonQuotations, mas pro que ainda NÃO foi finalizado — é o que
    // alimenta "Resultados de Cotações" na tela do representante, onde ele confirma ou
    // corta cada item antes de finalizar o pedido.
    @Transactional(readOnly = true)
    public List<WonQuotationSummary> findPendingFulfillmentResults(Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
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
    public RepresentativePerformance getRepresentativePerformance(Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        // Só últimos 30 dias — desempenho antigo não representa como o representante
        // está indo AGORA, e ia deixar o número "empacado" pra sempre em cima de coisa
        // de meses atrás. Um único corte na origem (aqui) já reflete em tudo que vem
        // depois: taxa de vitória, produtos, perdas, valor ganho.
        LocalDateTime performanceCutoff = LocalDateTime.now().minusDays(30);
        List<Bid> myBids = bidRepository.findBySupplierId(supplierId).stream()
                .filter(b -> b.getSubmittedAt() != null && b.getSubmittedAt().isAfter(performanceCutoff))
                .toList();

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
                .filter(q -> q.getPublishedAt() != null && q.getPublishedAt().isAfter(performanceCutoff))
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

    // Relatório detalhado, uma linha por LANCE — cruza cotação, fornecedor (empresa),
    // representante que enviou, e produto. Parte de todos os lances da cotação (não só
    // do vencedor de cada item) porque o admin também precisa ver o que um representante
    // digitou mesmo quando não venceu — onlyWinners é quem decide se filtra pra só
    // vencedor ou mostra tudo, com o campo "won" dizendo qual é qual. Só cotação FECHADA
    // entra (é resultado definitivo). Filtro de período usa a data de fechamento
    // (updatedAt) — mesma data que já uso em "Ganhei" e no PDF. Todo filtro é opcional.
    @Transactional(readOnly = true)
    public List<QuotationReportRow> getQuotationReport(
            LocalDateTime from, LocalDateTime to, Long supplierId, Long representativeId,
            String productQuery, boolean onlyWinners) {

        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);
        List<QuotationReportRow> rows = new ArrayList<>();
        String normalizedProductQuery = productQuery != null ? productQuery.trim().toLowerCase() : null;
        // Evita repetir a mesma consulta de confirmação pra cada item — todo item de um
        // mesmo (cotação, fornecedor) compartilha a MESMA resposta de "pedido enviado?",
        // então cacheia por combinação dentro dessa geração de relatório só.
        Map<String, Boolean> orderConfirmedCache = new HashMap<>();

        for (Quotation quotation : closedQuotations) {
            LocalDateTime closedAt = quotation.getUpdatedAt();
            if (from != null && (closedAt == null || closedAt.isBefore(from))) continue;
            if (to != null && (closedAt == null || closedAt.isAfter(to))) continue;

            List<Bid> bids = bidRepository.findByQuotationItem_QuotationId(quotation.getId());
            for (Bid bid : bids) {
                QuotationItem item = bid.getQuotationItem();
                boolean won = item.getWinningBid() != null && item.getWinningBid().getId().equals(bid.getId());
                if (onlyWinners && !won) continue;

                Supplier supplier = bid.getSupplier();
                if (supplierId != null && !supplier.getId().equals(supplierId)) continue;

                Representative rep = bid.getSubmittedBy();
                if (representativeId != null && (rep == null || !rep.getId().equals(representativeId))) continue;

                String productName = item.getProduct().getName();
                if (normalizedProductQuery != null && !normalizedProductQuery.isEmpty()
                        && !productName.toLowerCase().contains(normalizedProductQuery)) continue;

                String cacheKey = quotation.getId() + ":" + supplier.getId();
                boolean orderConfirmed = orderConfirmedCache.computeIfAbsent(cacheKey,
                        k -> orderFulfillmentConfirmationRepository.existsByQuotationIdAndSupplierId(quotation.getId(), supplier.getId()));

                rows.add(new QuotationReportRow(
                        quotation.getId(),
                        quotation.getName(),
                        closedAt,
                        supplier.getName(),
                        rep != null ? rep.getName() : "—",
                        productName,
                        item.getQuantity(),
                        bid.getValue(),
                        bid.getValue().multiply(item.getQuantity()),
                        won,
                        orderConfirmed
                ));
            }
        }

        rows.sort((a, b) -> {
            if (a.closedAt() == null || b.closedAt() == null) return 0;
            return b.closedAt().compareTo(a.closedAt());
        });

        return rows;
    }

    // Relatório de "ponto de compra" — quando reabrir pedido de cada produto pra não
    // ficar sem estoque. Só entram itens com vencedor definido e não cortados (sem
    // isso, não tem o que repor), e só quando dá pra calcular de verdade: precisa do
    // prazo de entrega (do lance vencedor, ou o padrão cadastrado no fornecedor quando
    // o lance não tiver o próprio prazo) e da projeção de venda efetiva do item.
    //
    // A conta, em 3 passos:
    //   chegada estimada   = fechamento da cotação + prazo de entrega
    //   esgotamento estimado = chegada + projeção de venda
    //   ponto de compra    = esgotamento − prazo de entrega (a folga pro PRÓXIMO pedido
    //                         chegar a tempo, assumindo prazo de entrega parecido)
    //
    // Ordenado por urgência (ponto de compra mais próximo primeiro) — é uma lista de
    // ação, não um extrato neutro.
    @Transactional(readOnly = true)
    public List<ReorderPointRow> getReorderPointReport() {
        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);
        List<ReorderPointRow> rows = new ArrayList<>();

        for (Quotation quotation : closedQuotations) {
            LocalDateTime closedAt = quotation.getUpdatedAt();
            if (closedAt == null) continue;

            List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotation.getId());
            for (QuotationItem item : items) {
                if (item.isFulfillmentCut()) continue;

                Bid winningBid = item.getWinningBid();
                if (winningBid == null) continue;

                Integer deliveryDeadlineDays = winningBid.getDeliveryDeadlineDays() != null
                        ? winningBid.getDeliveryDeadlineDays()
                        : winningBid.getSupplier().getDefaultDeliveryDeadlineDays();
                Integer projectionDays = item.getSalesProjectionDaysOverride() != null
                        ? item.getSalesProjectionDaysOverride()
                        : quotation.getDefaultSalesProjectionDays();
                if (deliveryDeadlineDays == null || projectionDays == null) continue;

                LocalDateTime estimatedArrivalDate = closedAt.plusDays(deliveryDeadlineDays);
                LocalDateTime estimatedDepletionDate = estimatedArrivalDate.plusDays(projectionDays);
                LocalDateTime reorderDate = estimatedDepletionDate.minusDays(deliveryDeadlineDays);
                long daysUntilReorder = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), reorderDate);

                rows.add(new ReorderPointRow(
                        quotation.getId(),
                        quotation.getName(),
                        closedAt,
                        item.getId(),
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getProduct().getBarcode(),
                        winningBid.getSupplier().getName(),
                        winningBid.getSubmittedBy() != null ? winningBid.getSubmittedBy().getName() : "—",
                        item.getQuantity(),
                        deliveryDeadlineDays,
                        projectionDays,
                        estimatedArrivalDate,
                        estimatedDepletionDate,
                        reorderDate,
                        daysUntilReorder
                ));
            }
        }

        rows.sort(Comparator.comparing(ReorderPointRow::reorderDate));
        return rows;
    }

    // Mesma ideia de linha-por-lance do relatório do admin, só que escopado a UMA
    // cotação e UM fornecedor — é o que o representante vê ao abrir o detalhe de uma
    // cotação em "Anteriores". Funciona pra CLOSED (mostra won) e EXPIRED (nunca teve
    // vencedor, então won sempre falso — mas ainda mostra o que foi digitado).
    @Transactional(readOnly = true)
    public List<QuotationReportRow> getMyBidsForQuotation(Long quotationId, Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + quotationId));

        List<Bid> bids = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                .filter(b -> b.getSupplier().getId().equals(supplierId))
                .toList();

        List<QuotationReportRow> rows = new ArrayList<>();
        for (Bid bid : bids) {
            QuotationItem item = bid.getQuotationItem();
            boolean won = item.getWinningBid() != null && item.getWinningBid().getId().equals(bid.getId());
            rows.add(new QuotationReportRow(
                    quotation.getId(),
                    quotation.getName(),
                    quotation.getUpdatedAt(),
                    bid.getSupplier().getName(),
                    bid.getSubmittedBy() != null ? bid.getSubmittedBy().getName() : "—",
                    item.getProduct().getName(),
                    item.getQuantity(),
                    bid.getValue(),
                    bid.getValue().multiply(item.getQuantity()),
                    won,
                    orderFulfillmentConfirmationRepository.existsByQuotationIdAndSupplierId(quotationId, supplierId)
            ));
        }
        return rows;
    }

    // "Não Cotar" — pra quando o representante abre uma cotação e não tem nenhum produto
    // pra ofertar. Idempotente (clicar duas vezes não duplica nem dá erro) e bloqueia se
    // já existir algum lance desse fornecedor nessa cotação — não faz sentido "declinar"
    // depois de já ter enviado preço pra pelo menos um item.
    @Transactional
    public void declineQuotation(Long quotationId, Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);

        Quotation quotation = findEntityById(quotationId);
        if (quotation.getStatus() != QuotationStatus.AVAILABLE) {
            throw new BusinessRuleException("Só é possível declinar uma cotação disponível.");
        }

        if (quotationDeclineRepository.existsByQuotationIdAndSupplierId(quotationId, supplierId)) {
            return;
        }

        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        boolean hasBids = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                .anyMatch(b -> b.getSupplier().getId().equals(supplierId));
        if (hasBids) {
            throw new BusinessRuleException("Esse fornecedor já enviou preços nessa cotação — não é possível declinar.");
        }

        if (supplier.getRepresentative() == null) {
            throw new BusinessRuleException("Esse fornecedor não tem representante vinculado.");
        }

        quotationDeclineRepository.save(QuotationDecline.builder()
                .quotation(quotation)
                .supplier(supplier)
                .declinedBy(supplier.getRepresentative())
                .declinedAt(LocalDateTime.now())
                .build());
    }

    // Marca "não tenho isso em estoque" — some da relação pro representante (não conta
    // mais em findFulfillmentSummaries, nem pendente nem finalizado) e fica registrado
    // pro admin ver o que foi cortado. Só antes de finalizar: depois disso o pedido tá
    // fechado de vez, igual não dá pra editar quantidade de cotação já CLOSED.
    @Transactional
    public void cutFulfillmentItem(Long quotationId, Long itemId, Long authenticatedRepresentativeId) {
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
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);

        if (orderFulfillmentConfirmationRepository.existsByQuotationIdAndSupplierId(item.getQuotation().getId(), supplierId)) {
            throw new BusinessRuleException("Esse pedido já foi finalizado — não é mais possível cortar itens.");
        }

        item.setFulfillmentCut(true);
        quotationItemRepository.save(item);
    }

    // Idempotente de propósito — clicar em "Finalizar Pedido" duas vezes (duplo toque,
    // rede lenta) não deve dar erro, só não faz nada na segunda vez.
    @Transactional
    public void finalizeFulfillment(Long quotationId, Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
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
                quotation.getUpdatedAt(),
                quotation.getDefaultSalesProjectionDays()
        );
    }

    private QuotationItemResponse toItemResponse(QuotationItem item) {
        Integer override = item.getSalesProjectionDaysOverride();
        Integer effective = override != null ? override : item.getQuotation().getDefaultSalesProjectionDays();
        return new QuotationItemResponse(
                item.getId(),
                item.getQuotation().getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getBarcode(),
                item.getQuantity(),
                item.getWinningBid() != null ? item.getWinningBid().getId() : null,
                item.isFulfillmentCut(),
                override,
                effective
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