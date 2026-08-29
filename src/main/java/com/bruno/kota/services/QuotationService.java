package com.bruno.kota.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.bruno.kota.dtos.*;
import com.bruno.kota.dtos.RepresentativePerformance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.CompanyEmailContact;
import com.bruno.kota.entities.OrderFulfillmentConfirmation;
import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationEligibleSupplier;
import com.bruno.kota.entities.QuotationDecline;
import com.bruno.kota.entities.QuotationEvent;
import com.bruno.kota.entities.QuotationEventType;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.OrderFulfillmentConfirmationRepository;
import com.bruno.kota.repositories.QuotationEventRepository;
import com.bruno.kota.repositories.CompanyEmailContactRepository;
import com.bruno.kota.repositories.QuotationDeclineRepository;
import com.bruno.kota.repositories.QuotationEligibleSupplierRepository;
import com.bruno.kota.repositories.ProductRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.SupplierGroupRepository;
import com.bruno.kota.repositories.SupplierRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final EmailService emailService;
    private final QuotationEventRepository quotationEventRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final CompanyEmailContactRepository companyEmailContactRepository;
    private final QuotationPdfService quotationPdfService;
    private final QuotationEligibleSupplierRepository quotationEligibleSupplierRepository;

    @Transactional(readOnly = true)
    public List<QuotationResponse> findAll() {
        // findAllWithGroup traz o grupo de fornecedores já junto (JOIN FETCH) — evita 1
        // query lazy por cotação em toResponse() só pra ler o nome do grupo. Essa é a
        // listagem usada em GET /quotations, a primeira chamada que o dashboard dispara.
        List<Quotation> quotations = quotationRepository.findAllWithGroup();
        if (quotations.isEmpty()) {
            return List.of();
        }

        // hasBids em lote — 1 query pra descobrir quais dessas cotações têm pelo menos um
        // lance, em vez de 1 existsByQuotationItem_QuotationId() por cotação (o overload
        // de toResponse SEM esse parâmetro faz isso, mas dentro de um .map() sobre a lista
        // inteira isso vira N+1 de novo).
        List<Long> ids = quotations.stream().map(Quotation::getId).toList();
        Set<Long> idsWithBids = new HashSet<>(bidRepository.findDistinctQuotationIdsWithBids(ids));

        return quotations.stream()
                .map(q -> toResponse(q, idsWithBids.contains(q.getId())))
                .toList();
    }

    // Paginação no backend pra tela "Cotações" (GET /quotations/by-status) — 15 por
    // página. statusFilter aceita os 5 status reais (DRAFT/AVAILABLE/REVIEWING/CLOSED/
    // EXPIRED) e os 2 nomes virtuais que o front usa nas abas "Publicadas"... na real só
    // "AWAITING_CLOSE" é virtual (a aba EXPIRED de verdade já é o status EXPIRED com
    // hasBids=false). Continua existindo o findAll() sem paginar — usado por telas que
    // precisam da lista inteira de uma vez (Dashboard, dropdown de rascunhos etc).
    @Transactional(readOnly = true)
    public PagedResponse<QuotationResponse> findByStatusPaged(String statusFilter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Quotation> result;
        if ("AWAITING_CLOSE".equals(statusFilter)) {
            result = quotationRepository.findExpiredWithGroupPaged(true, pageable);
        } else if ("EXPIRED".equals(statusFilter)) {
            result = quotationRepository.findExpiredWithGroupPaged(false, pageable);
        } else {
            QuotationStatus status;
            try {
                status = QuotationStatus.valueOf(statusFilter);
            } catch (IllegalArgumentException ex) {
                throw new BusinessRuleException("Status inválido: " + statusFilter);
            }
            result = quotationRepository.findByStatusWithGroupPaged(status, pageable);
        }

        List<Quotation> content = result.getContent();
        List<Long> ids = content.stream().map(Quotation::getId).toList();
        Set<Long> idsWithBids = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(bidRepository.findDistinctQuotationIdsWithBids(ids));

        List<QuotationResponse> responses = content.stream()
                .map(q -> toResponse(q, idsWithBids.contains(q.getId())))
                .toList();

        return new PagedResponse<>(responses, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public QuotationResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    // Lista de cotações da tela do representante — filtrada no backend (grupo do
    // fornecedor dele), sem status fixo: quem chama filtra por status do jeito que
    // precisar (Disponíveis, Histórico, ou achar uma específica pelo link do
    // WhatsApp). Único requisito aqui é nunca devolver Rascunho, que o representante
    // não pode ver em hipótese nenhuma. Faltou esse endpoint na leva de correções de
    // segurança de ontem: o front chamava o GET /quotations (que virou ADMIN-only, e
    // com razão — devolvia a lista inteira, de todo mundo, sem filtro nenhum) em três
    // lugares diferentes (aba Disponíveis, aba Anteriores, e abertura de link direto).
    @Transactional(readOnly = true)
    public List<QuotationResponse> findForSupplier(Long supplierId, Long authenticatedRepresentativeId) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        // Antes retornava cedo se o fornecedor não tivesse grupo nenhum — não dá mais
        // pra cortar caminho assim, porque um fornecedor sem grupo ainda pode enxergar
        // cotação em que foi adicionado como avulso (extraSuppliers).
        return quotationRepository.findAll().stream()
                .filter(q -> q.getStatus() != QuotationStatus.DRAFT)
                .filter(q -> isSupplierEligibleForQuotation(q, supplier))
                .map(this::toResponse)
                .toList();
    }

    // Checagem de acesso pra representante ver detalhe/itens/PDF de uma cotação —
    // ANTES não existia nenhuma (qualquer representante logado podia ver QUALQUER
    // cotação por id, inclusive Rascunho e de grupo que não é o dele). Devolve
    // ResourceNotFoundException (404) em vez de "sem permissão" (403) de propósito: não
    // queremos confirmar pra quem não tem acesso que aquele id de cotação existe.
    @Transactional(readOnly = true)
    public void validateRepresentativeCanViewQuotation(Long quotationId, Long representativeId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: id " + quotationId));
        if (quotation.getStatus() == QuotationStatus.DRAFT) {
            throw new ResourceNotFoundException("Cotação não encontrada: id " + quotationId);
        }
        boolean hasAccess = getEligibleSuppliers(quotation).stream()
                .anyMatch(s -> s.getRepresentative() != null && s.getRepresentative().getId().equals(representativeId));
        if (!hasAccess) {
            throw new ResourceNotFoundException("Cotação não encontrada: id " + quotationId);
        }
    }

    // Mesma checagem acima + confirma que o supplierId informado é mesmo do
    // representante — usado antes de gerar PDF de resultado (nunca pode baixar o PDF
    // "geral" da empresa nem o de outro fornecedor do mesmo grupo).
    @Transactional(readOnly = true)
    public void validateRepresentativeCanViewQuotationResult(Long quotationId, Long supplierId, Long representativeId) {
        validateRepresentativeCanViewQuotation(quotationId, representativeId);
        if (supplierId == null) {
            throw new BusinessRuleException("Informe o fornecedor.");
        }
        validateSupplierOwnership(supplierId, representativeId);
    }

    @Transactional(readOnly = true)
    public List<QuotationItemResponse> findItems(Long quotationId) {
        return findItems(quotationId, null);
    }

    // Dashboard de Economia — "quanto economizei comparando o menor lance com a média
    // dos lances", agrupado por grupo de fornecedores. Só entra item que teve vencedor
    // definido E mais de 1 lance (com 1 lance só, média == vencedor, economia zero por
    // definição — não tem com o que comparar). from/to filtram pela data de FECHAMENTO
    // da cotação (updatedAt quando virou CLOSED), igual ao Relatório de Cotações — sem
    // filtro, o controller já manda o mês corrente por padrão.
    @Transactional(readOnly = true)
    public SpendSavingsSummary getSpendSavings(LocalDateTime from, LocalDateTime to) {
        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);

        List<Long> quotationIds = closedQuotations.stream()
                .filter(q -> {
                    LocalDateTime closedAt = q.getUpdatedAt();
                    if (from != null && (closedAt == null || closedAt.isBefore(from))) return false;
                    if (to != null && (closedAt == null || closedAt.isAfter(to))) return false;
                    return true;
                })
                .map(Quotation::getId)
                .toList();

        if (quotationIds.isEmpty()) {
            return new SpendSavingsSummary(BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
        }

        List<Bid> bids = bidRepository.findByQuotationItem_QuotationIdInWithGroupAndWinner(quotationIds);

        // Agrupa lances por item — precisa ver TODOS os lances de um item de uma vez só
        // pra tirar a média (não dá pra calcular incrementalmente lance a lance).
        Map<Long, List<Bid>> bidsByItem = bids.stream()
                .collect(Collectors.groupingBy(b -> b.getQuotationItem().getId()));

        // -1L representa "sem grupo definido" — chave simples pra não precisar de Long
        // nulo em Map (que dá NPE em merge/getOrDefault dependendo do uso).
        Map<Long, BigDecimal> savingsByGroup = new LinkedHashMap<>();
        Map<Long, BigDecimal> spendByGroup = new LinkedHashMap<>();
        Map<Long, String> groupNames = new LinkedHashMap<>();
        Map<Long, Integer> itemCountByGroup = new LinkedHashMap<>();

        BigDecimal totalSavings = BigDecimal.ZERO;
        BigDecimal totalSpend = BigDecimal.ZERO;
        int totalItems = 0;

        for (List<Bid> itemBids : bidsByItem.values()) {
            QuotationItem item = itemBids.get(0).getQuotationItem();
            Bid winningBid = item.getWinningBid();
            if (winningBid == null) continue;

            // Preço de custo importado (opcional, ver QuotationImportService) é a base
            // mais precisa quando existe — economia de verdade frente ao que já se
            // pagava antes, em vez de uma estimativa contra a média dos lances dessa
            // cotação específica. Funciona mesmo com um lance só, porque não depende de
            // ter concorrência pra comparar. Sem custo importado, cai no comportamento
            // de sempre: média dos lances recebidos, que só faz sentido com pelo menos 2
            // lances — com 1 só não tem com o que comparar.
            BigDecimal baseline;
            if (item.getCostPrice() != null) {
                baseline = item.getCostPrice();
            } else if (itemBids.size() >= 2) {
                BigDecimal sum = itemBids.stream().map(Bid::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                baseline = sum.divide(BigDecimal.valueOf(itemBids.size()), 4, RoundingMode.HALF_UP);
            } else {
                continue;
            }

            BigDecimal quantity = item.getQuantity();
            BigDecimal itemSavings = baseline.subtract(winningBid.getValue()).multiply(quantity).max(BigDecimal.ZERO);
            BigDecimal itemSpend = winningBid.getValue().multiply(quantity);

            Quotation quotation = item.getQuotation();
            SupplierGroup group = safeGetSupplierGroup(quotation);
            Long groupId = group != null ? group.getId() : -1L;
            String groupName = group != null ? group.getName() : "Sem grupo";

            savingsByGroup.merge(groupId, itemSavings, BigDecimal::add);
            spendByGroup.merge(groupId, itemSpend, BigDecimal::add);
            groupNames.putIfAbsent(groupId, groupName);
            itemCountByGroup.merge(groupId, 1, Integer::sum);

            totalSavings = totalSavings.add(itemSavings);
            totalSpend = totalSpend.add(itemSpend);
            totalItems++;
        }

        List<SpendSavingsRow> rows = savingsByGroup.entrySet().stream()
                .map(e -> new SpendSavingsRow(
                        e.getKey() == -1L ? null : e.getKey(),
                        groupNames.get(e.getKey()),
                        e.getValue(),
                        spendByGroup.get(e.getKey()),
                        itemCountByGroup.get(e.getKey())
                ))
                .sorted(Comparator.comparing(SpendSavingsRow::totalSavings).reversed())
                .toList();

        return new SpendSavingsSummary(totalSavings, totalSpend, totalItems, rows);
    }

    // Com supplierId: busca TODOS os lances desse fornecedor nessa cotação numa consulta
    // só (não um SELECT por item) e já devolve o preço pré-preenchido junto de cada item.
    // Isso substitui o padrão antigo do frontend (buscar os itens, depois um GET /bids
    // por item, em paralelo) — pra uma cotação com 90 itens, eram 90 requisições extra
    // só pra saber "esse fornecedor já cotou isso?", e era exatamente isso que deixava
    // o primeiro carregamento da tela de lançamento lento.
    @Transactional(readOnly = true)
    public List<QuotationItemResponse> findItems(Long quotationId, Long supplierId) {
        findEntityById(quotationId);
        List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotationId);

        Map<Long, Bid> myBidByItemId = Map.of();
        if (supplierId != null) {
            myBidByItemId = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                    .filter(bid -> bid.getSupplier().getId().equals(supplierId))
                    .collect(Collectors.toMap(bid -> bid.getQuotationItem().getId(), bid -> bid));
        }

        List<QuotationItemResponse> result = new ArrayList<>();
        for (QuotationItem item : items) {
            Bid myBid = myBidByItemId.get(item.getId());
            result.add(toItemResponse(item, myBid));
        }
        return result;
    }

    @Transactional
    public QuotationResponse createManually(QuotationCreateRequest request, String performedBy) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessRuleException("Uma cotação precisa de pelo menos um item.");
        }
        validateGroupOrExtraSuppliersExclusive(request.supplierGroupId(), request.extraSupplierIds());
        // Prazo é opcional na criação (o admin pode definir depois) — mas, quando
        // informado, não pode já estar no passado. Sem essa checagem aqui, dava pra
        // criar (e até publicar, se a validação de publish() checasse só o momento da
        // publicação) uma cotação nascendo já vencida. Mensagem prefixada com
        // "expirationDate:" de propósito — é o formato que distributeFieldErrors() no
        // frontend espera pra rotear o erro pro campo certo (mq-expiration-date), em
        // vez de cair no fallback e aparecer embaixo do campo Nome.
        if (request.expirationDate() != null && request.expirationDate().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("expirationDate: O prazo de expiração não pode estar no passado.");
        }

        Quotation quotation = Quotation.builder()
                .name(request.name())
                .supplierGroup(resolveSupplierGroup(request.supplierGroupId()))
                .extraSuppliers(resolveExtraSuppliers(request.extraSupplierIds()))
                .expirationDate(request.expirationDate())
                .defaultSalesProjectionDays(request.defaultSalesProjectionDays())
                .build();
        quotation = quotationRepository.save(quotation);
        logEvent(quotation, QuotationEventType.CREATED, "Cotação criada manualmente.", performedBy);

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

    // Visão geral, cruzando TODAS as cotações fechadas — diferente do painel "Produtos
    // sem atendimento" de dentro de uma cotação (renderQdFulfillmentIssues no front),
    // que é só daquela cotação. Aqui o admin enxerga tudo de uma vez pra juntar itens de
    // cotações DIFERENTES numa cotação nova ou existente só, em vez de fazer isso
    // cotação por cotação.
    @Transactional(readOnly = true)
    public List<UnfulfilledItemRow> getUnfulfilledItemsReport() {
        List<Quotation> closedQuotations = quotationRepository.findByStatus(QuotationStatus.CLOSED);
        if (closedQuotations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = closedQuotations.stream().map(Quotation::getId).toList();

        List<UnfulfilledItemRow> result = new ArrayList<>();
        for (QuotationItem item : quotationItemRepository.findByQuotationIdInWithReorderDetails(ids)) {
            boolean noWinner = item.getWinningBid() == null;
            boolean cut = item.isFulfillmentCut();
            if (!noWinner && !cut) {
                continue;
            }
            result.add(new UnfulfilledItemRow(
                    item.getQuotation().getId(),
                    item.getQuotation().getName(),
                    item.getId(),
                    item.getProduct().getId(),
                    item.getProduct().getName(),
                    item.getProduct().getBarcode(),
                    item.getQuantity(),
                    cut
            ));
        }
        return result;
    }

    // Junta itens escolhidos de QUALQUER cotação fechada (ver getUnfulfilledItemsReport)
    // numa cotação nova em Rascunho, sem grupo/representante definido ainda — o admin
    // decide isso depois, editando a cotação recém-criada, igual qualquer outra criação
    // manual. "Adicionar a uma cotação existente" (o outro botão da tela) não precisa de
    // endpoint novo: reaproveita POST /quotations/{id}/items um item de cada vez, mesmo
    // caminho já usado pelo painel de dentro da cotação.
    @Transactional
    public QuotationResponse createFromUnfulfilledItems(List<Long> quotationItemIds) {
        if (quotationItemIds == null || quotationItemIds.isEmpty()) {
            throw new BusinessRuleException("Selecione pelo menos um item.");
        }

        Quotation quotation = Quotation.builder()
                .name("Produtos sem atendimento")
                .build();
        quotation = quotationRepository.save(quotation);

        for (Long itemId : quotationItemIds) {
            QuotationItem source = quotationItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: id " + itemId));
            QuotationItem newItem = QuotationItem.builder()
                    .quotation(quotation)
                    .product(source.getProduct())
                    .quantity(source.getQuantity())
                    .build();
            quotationItemRepository.save(newItem);
        }

        return toResponse(quotation);
    }

    // Mesma ideia de duplicateUnquotedItems(), mas pros itens que o representante
    // venceu e DEPOIS confirmou que não tinha em estoque (fulfillmentCut) — cenário
    // diferente de "ninguém ofertou": aqui alguém ofertou e não conseguiu entregar, e o
    // caminho natural é tentar de novo, possivelmente com outro fornecedor.
    @Transactional
    public QuotationResponse duplicateCutItems(Long id) {
        Quotation original = findEntityById(id);
        List<QuotationItem> cutItems = quotationItemRepository.findByQuotationId(id).stream()
                .filter(QuotationItem::isFulfillmentCut)
                .toList();
        if (cutItems.isEmpty()) {
            throw new BusinessRuleException("Não há itens cortados por falta de estoque nessa cotação.");
        }
        return duplicateInternal(original, cutItems, " (falta de estoque)");
    }

    private QuotationResponse duplicateInternal(Quotation original, List<QuotationItem> itemsToClone, String nameSuffix) {
        Quotation copy = Quotation.builder()
                .name(original.getName() + nameSuffix)
                .supplierGroup(safeGetSupplierGroup(original))
                .extraSuppliers(new LinkedHashSet<>(original.getExtraSuppliers()))
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

        validateGroupOrExtraSuppliersExclusive(request.supplierGroupId(), request.extraSupplierIds());

        if (quotation.getStatus() == QuotationStatus.AVAILABLE
                && request.expirationDate() != null
                && quotation.getExpirationDate() != null
                && request.expirationDate().isBefore(quotation.getExpirationDate())) {
            throw new BusinessRuleException("Não é permitido encurtar o prazo de uma cotação já publicada.");
        }

        // Prazo adiado depois que o lembrete já foi mandado pro prazo antigo? Libera de
        // novo — senão ninguém recebe aviso nenhum do prazo novo (reminderSentAt
        // continuaria preenchido pra sempre, e o scheduler nunca mais pegaria essa
        // cotação, mesmo com um prazo novo bem diferente do que gerou o lembrete original).
        if (quotation.getReminderSentAt() != null
                && request.expirationDate() != null
                && !request.expirationDate().isEqual(quotation.getExpirationDate())) {
            quotation.setReminderSentAt(null);
        }

        quotation.setName(request.name());
        quotation.setSupplierGroup(resolveSupplierGroup(request.supplierGroupId()));
        quotation.getExtraSuppliers().clear();
        quotation.getExtraSuppliers().addAll(resolveExtraSuppliers(request.extraSupplierIds()));
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
        // Sem isso, a exclusão quebrava com violação de FK — toda cotação já nasce com
        // um evento CREATED (ver createManually/importFile), e quotation_id é NOT NULL
        // em quotation_events. Rascunho nunca chega a ter mais eventos que isso (só
        // publicação/prorrogação/fechamento geram os outros tipos), mas limpa tudo
        // mesmo assim, por segurança.
        quotationEventRepository.deleteByQuotationId(id);
        quotationRepository.delete(quotation);
    }

    @Transactional
    public QuotationResponse publish(Long id, String performedBy) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new BusinessRuleException("Só é possível publicar uma cotação que esteja em DRAFT.");
        }
        if (getEligibleSuppliers(quotation).isEmpty()) {
            throw new BusinessRuleException("Defina o grupo de fornecedores ou adicione fornecedores avulsos antes de publicar.");
        }
        if (quotation.getExpirationDate() == null) {
            throw new BusinessRuleException("Defina o prazo de expiração antes de publicar.");
        }
        if (quotation.getExpirationDate().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("O prazo de expiração já passou — ajuste a data antes de publicar.");
        }

        quotation.setStatus(QuotationStatus.AVAILABLE);
        quotation.setPublishedAt(LocalDateTime.now());
        Quotation saved = quotationRepository.save(quotation);
        snapshotEligibleSuppliers(saved);
        logEvent(saved, QuotationEventType.PUBLISHED,
                "Cotação publicada — prazo até " + fmtEvent(saved.getExpirationDate()) + ".", performedBy);

        notifyEligibleRepresentativesOfPublish(saved);

        return toResponse(saved);
    }

    // Republicar (mesmo Nº) — só pra cotação Expirada de verdade (ninguém respondeu a
    // tempo). Diferente de duplicate(): não cria uma cotação nova, reabre a mesma linha
    // (mesmo id, mesmos itens já cadastrados) direto pra AVAILABLE com um novo prazo.
    // Se já tiver lance registrado é a aba "Aguardando Fechamento" — ali o certo é Fechar
    // pra calcular vencedores, não republicar (senão o lance que já chegou fica órfão de
    // um prazo que nunca mais bate com ele). reminderSentAt volta pra null pra o lembrete
    // de prazo poder disparar de novo nesse novo ciclo.
    @Transactional
    public QuotationResponse republish(Long id, QuotationExtendRequest request, String performedBy) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.EXPIRED) {
            throw new BusinessRuleException("Só é possível republicar uma cotação Expirada.");
        }
        boolean hasBids = bidRepository.existsByQuotationItem_QuotationId(id);
        if (hasBids) {
            throw new BusinessRuleException("Essa cotação já recebeu lances — use \"Fechar\" para calcular os vencedores, em vez de republicar.");
        }
        if (request.expirationDate().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("O novo prazo de expiração precisa ser no futuro.");
        }

        quotation.setStatus(QuotationStatus.AVAILABLE);
        quotation.setExpirationDate(request.expirationDate());
        quotation.setPublishedAt(LocalDateTime.now());
        quotation.setReminderSentAt(null);
        Quotation saved = quotationRepository.save(quotation);
        snapshotEligibleSuppliers(saved);
        logEvent(saved, QuotationEventType.REPUBLISHED,
                "Cotação republicada (mesmo Nº) — prazo até " + fmtEvent(saved.getExpirationDate()) + ".", performedBy);

        notifyEligibleRepresentativesOfPublish(saved);

        return toResponse(saved);
    }

    // Um e-mail por representante elegível (dono de fornecedor do grupo da cotação) —
    // mesma lista de "quem deveria responder" usada em getRepresentativeFillRate e
    // getRepresentativeResponseStatus. EmailService já garante internamente que uma
    // falha de envio não propaga — a publicação em si nunca é derrubada por causa disso.
    // Fotografa quem estava elegível a responder essa cotação NESSE momento (publicação
    // ou republicação) — é o que permite calcular uma taxa de resposta histórica de
    // verdade depois (ver StatisticsService), em vez de reconstruir com a composição
    // ATUAL do grupo, que já pode ter mudado. Limpa fotografia anterior antes de gravar
    // — importa só pra republicação: se o grupo mudou entre um ciclo e outro, a
    // fotografia precisa refletir o ciclo novo, não acumular os dois.
    private void snapshotEligibleSuppliers(Quotation quotation) {
        quotationEligibleSupplierRepository.deleteByQuotationId(quotation.getId());
        List<Supplier> suppliers = getEligibleSuppliers(quotation);
        if (suppliers.isEmpty()) {
            return;
        }
        List<QuotationEligibleSupplier> rows = suppliers.stream()
                .map(s -> QuotationEligibleSupplier.builder()
                        .quotation(quotation)
                        .supplier(s)
                        .representative(s.getRepresentative())
                        .build())
                .toList();
        quotationEligibleSupplierRepository.saveAll(rows);
    }

    private void notifyEligibleRepresentativesOfPublish(Quotation quotation) {
        List<Supplier> eligibleSuppliers = getEligibleSuppliers(quotation);
        if (eligibleSuppliers.isEmpty()) {
            return;
        }
        // Extrai nome/e-mail AQUI, dentro da transação — é a última chance segura de ler
        // esses campos das entidades. O envio em si roda em outra thread (@Async), sem
        // acesso à sessão do Hibernate; passar as entidades direto pra lá arriscaria
        // LazyInitializationException na hora de ler representative.getName().
        List<Representative> reps = distinctById(eligibleSuppliers.stream()
                .map(Supplier::getRepresentative)
                .filter(Objects::nonNull)
                .toList());
        List<EmailService.RepContact> eligibleReps = reps.stream()
                .map(r -> new EmailService.RepContact(r.getName(), r.getEmail()))
                .toList();
        emailService.notifyQuotationPublished(quotation.getId(), quotation.getName(), quotation.getExpirationDate(), eligibleReps);
        if (!eligibleReps.isEmpty()) {
            logEvent(quotation, QuotationEventType.EMAIL_SENT,
                    "E-mail de publicação disparado para " + eligibleReps.size() + " representante" + (eligibleReps.size() == 1 ? "" : "s") + ".");
        }
    }

    // Chamado pelo QuotationReminderScheduler — 1 vez por cotação (o scheduler já filtra
    // pra só trazer cotações com reminderSentAt nulo, e marca depois de chamar isso).
    // "Ainda não respondeu" = nem lance, nem "Não Cotar" — mesma definição de PENDING
    // usada em getRepresentativeResponseStatus e getRepresentativeFillRate, só que aqui
    // devolvendo os representantes de verdade (com e-mail), não o DTO de status.
    @Transactional
    public void sendDeadlineReminder(Long quotationId) {
        Quotation quotation = findEntityById(quotationId);
        List<Supplier> eligibleSuppliers = getEligibleSuppliers(quotation);
        if (eligibleSuppliers.isEmpty()) {
            return;
        }

        Set<Long> submittedSupplierIds = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                .map(bid -> bid.getSupplier().getId())
                .collect(Collectors.toSet());
        Set<Long> declinedSupplierIds = quotationDeclineRepository.findByQuotationId(quotationId).stream()
                .map(d -> d.getSupplier().getId())
                .collect(Collectors.toSet());

        List<Representative> pendingReps = distinctById(eligibleSuppliers.stream()
                .filter(s -> !submittedSupplierIds.contains(s.getId()) && !declinedSupplierIds.contains(s.getId()))
                .map(Supplier::getRepresentative)
                .filter(Objects::nonNull)
                .toList());

        if (!pendingReps.isEmpty()) {
            // Mesma lógica de notifyEligibleRepresentativesOfPublish: extrai nome/e-mail
            // aqui dentro, antes de cruzar pra thread assíncrona do @Async.
            List<EmailService.RepContact> contacts = pendingReps.stream()
                    .map(r -> new EmailService.RepContact(r.getName(), r.getEmail()))
                    .toList();
            emailService.notifyDeadlineApproaching(quotation.getId(), quotation.getName(), quotation.getExpirationDate(), contacts);
            logEvent(quotation, QuotationEventType.REMINDER_SENT,
                    "Lembrete de prazo enviado a " + pendingReps.size() + " representante" + (pendingReps.size() == 1 ? "" : "s") + " pendente" + (pendingReps.size() == 1 ? "" : "s") + ".");
        }
    }

    // Prorrogação de prazo — ação própria, separada de update(), disponível só com a
    // cotação Disponível (não faz sentido "prorrogar" um Rascunho, que ainda nem tem
    // representante vendo prazo nenhum). Só aceita data POSTERIOR à atual (mesma regra
    // de "não encurtar" do update(), só que aqui é a única coisa que essa ação faz,
    // então vira erro direto em vez de silenciosamente ignorar). Ao contrário do
    // lembrete de prazo terminando (só quem não respondeu), a notificação de
    // prorrogação vai pra TODO representante elegível — quem já respondeu também pode
    // querer revisar agora que sobrou mais tempo.
    @Transactional
    public QuotationResponse extendDeadline(Long id, QuotationExtendRequest request, String performedBy) {
        Quotation quotation = findEntityById(id);

        if (quotation.getStatus() != QuotationStatus.AVAILABLE) {
            throw new BusinessRuleException("Só é possível prorrogar o prazo de uma cotação disponível.");
        }
        if (quotation.getExpirationDate() != null && !request.expirationDate().isAfter(quotation.getExpirationDate())) {
            throw new BusinessRuleException("O novo prazo precisa ser posterior ao prazo atual.");
        }

        // Mesmo motivo do update(): se o lembrete de prazo já saiu pro prazo antigo,
        // libera de novo pro prazo novo — senão o scheduler nunca mais notificaria essa
        // cotação, mesmo com um prazo bem mais longe do que gerou o lembrete original.
        LocalDateTime previousExpiration = quotation.getExpirationDate();
        quotation.setReminderSentAt(null);
        quotation.setExpirationDate(request.expirationDate());
        Quotation saved = quotationRepository.save(quotation);
        logEvent(saved, QuotationEventType.DEADLINE_EXTENDED,
                "Prazo prorrogado de " + fmtEvent(previousExpiration) + " para " + fmtEvent(saved.getExpirationDate()) + ".", performedBy);

        notifyEligibleRepresentativesOfExtension(saved);

        return toResponse(saved);
    }

    private void notifyEligibleRepresentativesOfExtension(Quotation quotation) {
        List<Supplier> eligibleSuppliers = getEligibleSuppliers(quotation);
        if (eligibleSuppliers.isEmpty()) {
            return;
        }
        List<Representative> reps = distinctById(eligibleSuppliers.stream()
                .map(Supplier::getRepresentative)
                .filter(Objects::nonNull)
                .toList());
        List<EmailService.RepContact> eligibleReps = reps.stream()
                .map(r -> new EmailService.RepContact(r.getName(), r.getEmail()))
                .toList();
        emailService.notifyDeadlineExtended(quotation.getId(), quotation.getName(), quotation.getExpirationDate(), eligibleReps);
        if (!eligibleReps.isEmpty()) {
            logEvent(quotation, QuotationEventType.EMAIL_SENT,
                    "E-mail de prorrogação de prazo disparado para " + eligibleReps.size() + " representante" + (eligibleReps.size() == 1 ? "" : "s") + ".");
        }
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
    public QuotationCloseResult confirmClose(Long id, ConfirmCloseRequest request, String performedBy) {
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
        Quotation saved = quotationRepository.save(quotation);
        logEvent(saved, QuotationEventType.CLOSED, "Cotação fechada — vencedores confirmados.", performedBy);

        notifyEligibleRepresentativesOfClose(saved, items);
        purchaseOrderService.createForClosedQuotation(saved, items);
        notifyInternalContactsOfClose(saved);

        return new QuotationCloseResult(true, toResponse(saved), List.of(), List.of());
    }

    // Manda pra TODO representante elegível (grupo + fornecedores avulsos), tenha ele
    // vencido item nenhum ou não — é o que foi combinado.
    private void notifyEligibleRepresentativesOfClose(Quotation quotation, List<QuotationItem> items) {
        List<Supplier> eligibleSuppliers = getEligibleSuppliers(quotation);
        if (eligibleSuppliers.isEmpty()) {
            return;
        }
        List<Representative> eligibleReps = distinctById(eligibleSuppliers.stream()
                .map(Supplier::getRepresentative)
                .filter(Objects::nonNull)
                .toList());

        for (Representative rep : eligibleReps) {
            List<QuotationItem> wonItems = items.stream()
                    .filter(item -> item.getWinningBid() != null
                            && item.getWinningBid().getSubmittedBy() != null
                            && item.getWinningBid().getSubmittedBy().getId().equals(rep.getId()))
                    .toList();
            // Mesmo motivo de sempre: extrai os valores simples (nome do produto,
            // quantidade, preço) aqui dentro, ainda na transação — o método @Async não
            // teria como resolver item.getProduct() nem item.getWinningBid() sozinho
            // numa thread separada, sem sessão do Hibernate.
            List<EmailService.WonItemLine> wonLines = wonItems.stream()
                    .map(item -> new EmailService.WonItemLine(
                            item.getProduct().getName(), item.getQuantity(), item.getWinningBid().getValue()))
                    .toList();
            emailService.notifyQuotationClosed(quotation.getName(), new EmailService.RepContact(rep.getName(), rep.getEmail()), wonLines);
        }
        if (!eligibleReps.isEmpty()) {
            logEvent(quotation, QuotationEventType.EMAIL_SENT,
                    "E-mail de resultado disparado para " + eligibleReps.size() + " representante" + (eligibleReps.size() == 1 ? "" : "s") + ".");
        }
    }

    // Manda o PDF de resultado por e-mail pra quem estiver cadastrado em "Dados da
    // Empresa → Contatos de E-mail" (pensado pro CPD/financeiro conferir pedidos sem
    // precisar logar no sistema). Gera o MESMO PDF do botão "Baixar PDF do Resultado" —
    // reaproveita QuotationPdfService.generateResultPdf(), não duplica lógica de
    // montagem de documento nenhuma. "melhor esforço" de propósito: se não tiver
    // nenhum contato cadastrado, ou se a geração do PDF falhar por qualquer motivo, o
    // fechamento da cotação em si não pode ser afetado — por isso o try/catch aqui
    // (diferente do EmailService, que já é melhor-esforço por padrão, gerar o PDF
    // acontece SÍNCRONO, ainda dentro dessa transação, e uma falha ali não pode subir).
    private void notifyInternalContactsOfClose(Quotation quotation) {
        List<CompanyEmailContact> contacts = companyEmailContactRepository.findAll();
        if (contacts.isEmpty()) {
            return;
        }
        try {
            byte[] pdfBytes = quotationPdfService.generateResultPdf(quotation.getId());
            List<EmailService.RepContact> recipients = contacts.stream()
                    .map(c -> new EmailService.RepContact(c.getName(), c.getEmail()))
                    .toList();
            emailService.notifyInternalContactsOfClose(String.valueOf(quotation.getId()), quotation.getName(), recipients, pdfBytes);
        } catch (Exception e) {
            log.error("Falha ao gerar/enviar PDF de resultado pros contatos internos (cotação {}): {}", quotation.getId(), e.getMessage());
        }
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
        // Traz o grupo de fornecedores já junto (JOIN FETCH), então quotation.getSupplierGroup()
        // logo abaixo não dispara mais uma query por cotação.
        List<Quotation> available = quotationRepository.findByStatusWithGroup(QuotationStatus.AVAILABLE);
        if (available.isEmpty()) {
            return List.of();
        }

        List<Long> quotationIds = available.stream().map(Quotation::getId).toList();

        // Antes: 1 query de lances + 1 query de "Não Cotar" POR cotação disponível, e
        // cada bid.getSubmittedBy() dentro do loop disparava mais uma query (N+1 dentro
        // do N+1). Agora: 1 query de cada, pra TODAS as cotações disponíveis de uma vez,
        // já agrupada por cotação em memória.
        Map<Long, Set<Long>> submittedRepIdsByQuotation = bidRepository
                .findByQuotationItem_QuotationIdInWithSubmitter(quotationIds).stream()
                .collect(Collectors.groupingBy(
                        b -> b.getQuotationItem().getQuotation().getId(),
                        Collectors.mapping(b -> b.getSubmittedBy().getId(), Collectors.toSet())));

        // "Não Cotar" conta como resposta pra taxa de participação — o representante
        // olhou a cotação e declarou explicitamente que não tem nada a oferecer, o que é
        // bem diferente de simplesmente nunca ter aberto a cotação.
        Map<Long, Set<Long>> declinedRepIdsByQuotation = quotationDeclineRepository
                .findByQuotationIdInWithDetails(quotationIds).stream()
                .collect(Collectors.groupingBy(
                        d -> d.getQuotation().getId(),
                        Collectors.mapping(d -> d.getDeclinedBy().getId(), Collectors.toSet())));

        List<QuotationFillRate> rates = new ArrayList<>();

        // Antes cacheava representantes elegíveis por grupo (várias cotações Disponíveis
        // costumam compartilhar o mesmo grupo). Com fornecedor avulso, elegibilidade
        // passou a poder variar cotação a cotação mesmo dentro do mesmo grupo — perdeu o
        // cache, mas o volume aqui é sempre "cotações Disponíveis agora" (poucas
        // dezenas), então recalcular por cotação não pesa.
        for (Quotation quotation : available) {
            Set<Long> eligibleRepIds = getEligibleSuppliers(quotation).stream()
                    .map(Supplier::getRepresentative)
                    .filter(Objects::nonNull)
                    .map(Representative::getId)
                    .collect(Collectors.toSet());

            if (eligibleRepIds.isEmpty()) {
                continue;
            }

            Set<Long> respondedRepIds = new HashSet<>(
                    submittedRepIdsByQuotation.getOrDefault(quotation.getId(), Set.of()));
            respondedRepIds.addAll(declinedRepIdsByQuotation.getOrDefault(quotation.getId(), Set.of()));

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
    // Status é por (fornecedor, representante) — NUNCA só por representante. Antes tinha
    // um bug aqui: quando o mesmo representante está vinculado a mais de um fornecedor
    // do grupo (acontece bastante — um representante atende várias empresas), cotar por
    // UM fornecedor fazia os DOIS aparecerem como "enviou lance", porque a checagem só
    // olhava o ID do representante, ignorando qual fornecedor ele estava representando
    // naquele lance específico. Bid e QuotationDecline sempre têm o fornecedor
    // explícito — é isso que precisa bater, não só quem digitou.
    // Separado da versão paginada abaixo porque o cálculo (cruzar fornecedores do grupo
    // com quem já deu lance/recusou) é sempre feito por inteiro — não dá pra "paginar a
    // consulta" de verdade, já que o status de cada fornecedor não é uma coluna pronta no
    // banco. Compensa: normalmente é um grupo só (dezenas a poucas centenas de
    // fornecedores), então computar tudo em memória é rápido; a paginação/busca abaixo
    // serve pra não mandar a lista inteira pro navegador de uma vez só.
    private List<RepresentativeResponseStatus> computeRepresentativeResponseStatus(Long quotationId) {
        Quotation quotation = findEntityById(quotationId);
        List<Supplier> eligibleSuppliers = getEligibleSuppliers(quotation);
        if (eligibleSuppliers.isEmpty()) {
            return List.of();
        }

        Set<Long> submittedSupplierIds = bidRepository.findByQuotationItem_QuotationId(quotationId).stream()
                .map(bid -> bid.getSupplier().getId())
                .collect(Collectors.toSet());
        Set<Long> declinedSupplierIds = quotationDeclineRepository.findByQuotationId(quotationId).stream()
                .map(d -> d.getSupplier().getId())
                .collect(Collectors.toSet());

        List<RepresentativeResponseStatus> result = new ArrayList<>();
        for (Supplier supplier : eligibleSuppliers) {
            Representative rep = supplier.getRepresentative();
            if (rep == null) {
                continue;
            }
            String status = submittedSupplierIds.contains(supplier.getId()) ? "SUBMITTED"
                    : declinedSupplierIds.contains(supplier.getId()) ? "DECLINED"
                    : "PENDING";
            result.add(new RepresentativeResponseStatus(rep.getId(), supplier.getId(), rep.getName(), supplier.getName(), status));
        }

        Map<String, Integer> statusOrder = Map.of("PENDING", 0, "DECLINED", 1, "SUBMITTED", 2);
        result.sort(Comparator.<RepresentativeResponseStatus>comparingInt(r -> statusOrder.get(r.status()))
                .thenComparing(RepresentativeResponseStatus::representativeName));
        return result;
    }

    // search filtra por nome do representante OU do fornecedor (o admin pode lembrar de
    // qualquer um dos dois na hora de procurar). page/size cortam a lista já filtrada —
    // o front só recebe a fatia que vai mostrar, não a lista inteira do grupo.
    // totalGroupSize/respondedCount vêm do grupo INTEIRO, sem o filtro de busca aplicado —
    // são pro resumo fixo do topo do modal, que não deve mudar conforme o admin digita.
    @Transactional(readOnly = true)
    public RepresentativeStatusPageResponse getRepresentativeResponseStatus(
            Long quotationId, String search, int page, int size) {
        List<RepresentativeResponseStatus> all = computeRepresentativeResponseStatus(quotationId);
        int respondedCount = (int) all.stream().filter(r -> !"PENDING".equals(r.status())).count();

        String normalizedSearch = search != null ? search.trim().toLowerCase() : "";
        List<RepresentativeResponseStatus> filtered = normalizedSearch.isEmpty()
                ? all
                : all.stream()
                    .filter(r -> r.representativeName().toLowerCase().contains(normalizedSearch)
                            || r.supplierName().toLowerCase().contains(normalizedSearch))
                    .toList();

        int totalElements = filtered.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<RepresentativeResponseStatus> content = filtered.subList(fromIndex, toIndex);

        return new RepresentativeStatusPageResponse(
                content, page, size, totalElements, totalPages, all.size(), respondedCount);
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

    // 1 evento por ENVIO no histórico, não 1 por item — antes, BidService.submit()
    // gravava um "LANCE RECEBIDO" pra cada item, o que enchia o "Ver Histórico" de
    // dezenas de linhas idênticas quando o representante mandava uma cotação com muitos
    // produtos de uma vez (o representante.html manda um POST /bids por item, tudo em
    // paralelo). Chamado pelo front UMA VEZ, depois que todos os itens da leva já
    // terminaram de salvar com sucesso.
    @Transactional
    public void logBidSubmission(Long quotationId, Long supplierId, Long authenticatedRepresentativeId, int itemCount, String impersonatedBy) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        Quotation quotation = findEntityById(quotationId);
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + supplierId));

        logEvent(quotation, QuotationEventType.BID_RECEIVED,
                "Cotação enviada por " + supplier.getName() + " — " + itemCount + " item" + (itemCount == 1 ? "" : "s") + ".",
                impersonatedBy != null ? impersonatedBy + " (via \"Ver como\" o representante)" : null);
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

    // Versão "card" de findWonQuotations — mesmo cálculo, mas descarta a lista de itens
    // antes de devolver pro controller, ficando só com a contagem e o total. É o que
    // "O que eu ganhei" usa pra montar os cards fechados; os itens de verdade são
    // buscados à parte, paginados, só quando o representante expande um card
    // (getWonItemsPage) — evita mandar a cotação inteira (as vezes dezenas de itens)
    // pra tela antes mesmo do representante decidir se vai olhar aquele card.
    @Transactional(readOnly = true)
    public List<WonQuotationCardResponse> findWonQuotationCards(Long supplierId, Long authenticatedRepresentativeId) {
        return findWonQuotations(supplierId, authenticatedRepresentativeId).stream()
                .map(q -> new WonQuotationCardResponse(q.quotationId(), q.quotationName(), q.closedAt(), q.items().size(), q.total()))
                .toList();
    }

    // Itens de UMA cotação ganha, paginados — chamado sob demanda quando o card expande
    // em "O que eu ganhei" (e de novo, com size grande, na hora de montar a mensagem do
    // WhatsApp, que precisa da lista inteira). Mesmo filtro de sempre (vencedor é esse
    // fornecedor E o item não foi cortado por falta de estoque depois) só que restrito a
    // uma cotação, em vez de percorrer todas as fechadas do sistema.
    @Transactional(readOnly = true)
    public PagedResponse<WonQuotationItem> getWonItemsPage(Long quotationId, Long supplierId, Long authenticatedRepresentativeId, int page, int size) {
        validateSupplierOwnership(supplierId, authenticatedRepresentativeId);
        findEntityById(quotationId);

        List<WonQuotationItem> wonItems = new ArrayList<>();
        for (QuotationItem item : quotationItemRepository.findByQuotationId(quotationId)) {
            Bid winner = item.getWinningBid();
            if (winner == null || !winner.getSupplier().getId().equals(supplierId) || item.isFulfillmentCut()) {
                continue;
            }
            BigDecimal subtotal = winner.getValue().multiply(item.getQuantity());
            wonItems.add(new WonQuotationItem(
                    item.getId(), item.getProduct().getName(), item.getProduct().getBarcode(),
                    item.getQuantity(), winner.getValue(), subtotal
            ));
        }

        int totalElements = wonItems.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<WonQuotationItem> content = wonItems.subList(fromIndex, toIndex);

        return new PagedResponse<>(content, page, size, totalElements, totalPages);
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

        if (closedQuotations.isEmpty()) {
            return result;
        }

        List<Long> closedIds = closedQuotations.stream().map(Quotation::getId).toList();

        // Antes: 1 exists() + 1 query de itens POR cotação fechada dentro do loop, e
        // dentro do item, winner.getSupplier() e item.getProduct() lazy disparavam mais
        // uma query cada (N+1 dentro do N+1) — exatamente o mesmo padrão do relatório de
        // Ponto de Compra. Agora: 1 query pra saber quais cotações já foram finalizadas
        // por esse fornecedor, e 1 query pra todos os itens já com tudo pré-carregado.
        Set<Long> finalizedQuotationIds = new HashSet<>(orderFulfillmentConfirmationRepository
                .findQuotationIdsBySupplierIdAndQuotationIdIn(supplierId, closedIds));

        List<Quotation> relevantQuotations = closedQuotations.stream()
                .filter(q -> finalizedQuotationIds.contains(q.getId()) == finalized)
                .toList();

        if (relevantQuotations.isEmpty()) {
            return result;
        }

        List<Long> relevantIds = relevantQuotations.stream().map(Quotation::getId).toList();
        Map<Long, List<QuotationItem>> itemsByQuotation = quotationItemRepository
                .findByQuotationIdInWithReorderDetails(relevantIds).stream()
                .collect(Collectors.groupingBy(qi -> qi.getQuotation().getId()));

        for (Quotation quotation : relevantQuotations) {
            List<QuotationItem> items = itemsByQuotation.getOrDefault(quotation.getId(), List.of());
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
        // findBySupplierIdWithDetails traz item/produto/cotação/grupo/vencedor já
        // pré-carregados — antes, cada acesso a bid.getQuotationItem().getProduct() etc.
        // dentro do loop abaixo disparava uma query lazy por lance do fornecedor.
        List<Bid> myBids = bidRepository.findBySupplierIdWithDetails(supplierId).stream()
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

        // findPublishedSince já filtra status <> DRAFT e publishedAt > cutoff DIRETO no
        // banco (antes era quotationRepository.findAll() — a TABELA INTEIRA de cotações,
        // sem filtro nenhum, filtrada em memória depois) e já traz o grupo junto. Filtro
        // de elegibilidade agora passa por isSupplierEligibleForQuotation() — não é mais
        // só "meu grupo bate com o da cotação", cobre fornecedor avulso também.
        List<Quotation> published = quotationRepository.findPublishedSince(performanceCutoff).stream()
                .filter(q -> isSupplierEligibleForQuotation(q, supplier))
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

        // Filtra por período ANTES de buscar os lances — reduz o lote que vai pra query
        // única abaixo, em vez de buscar tudo e descartar depois.
        List<Quotation> quotationsInRange = closedQuotations.stream()
                .filter(q -> {
                    LocalDateTime closedAt = q.getUpdatedAt();
                    if (from != null && (closedAt == null || closedAt.isBefore(from))) return false;
                    if (to != null && (closedAt == null || closedAt.isAfter(to))) return false;
                    return true;
                })
                .toList();

        if (quotationsInRange.isEmpty()) {
            return rows;
        }

        List<Long> quotationIds = quotationsInRange.stream().map(Quotation::getId).toList();

        // Antes: 1 query de lances POR cotação (N+1), e dentro do loop, bid.getSupplier(),
        // bid.getSubmittedBy() e item.getProduct() eram lazy e cada acesso disparava mais
        // uma query por lance (N+1 dentro do N+1). Agora: 1 query só, já com tudo pré-carregado.
        List<Bid> bids = bidRepository.findByQuotationItem_QuotationIdInWithReportDetails(quotationIds);

        for (Bid bid : bids) {
            QuotationItem item = bid.getQuotationItem();
            Quotation quotation = item.getQuotation();
            LocalDateTime closedAt = quotation.getUpdatedAt();

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

        List<Long> quotationIds = closedQuotations.stream()
                .filter(q -> q.getUpdatedAt() != null)
                .map(Quotation::getId)
                .toList();

        if (quotationIds.isEmpty()) {
            return rows;
        }

        // Antes: 1 query de itens POR cotação fechada (N+1), e dentro do loop,
        // item.getProduct(), item.getWinningBid(), winningBid.getSupplier() e
        // winningBid.getSubmittedBy() eram lazy — cada acesso disparava mais uma query
        // por item (N+1 dentro do N+1). Numa base com histórico de cotações, isso vira
        // centenas de queries só pra montar esse relatório. Agora: 1 query só, com tudo
        // pré-carregado via JOIN FETCH.
        List<QuotationItem> items = quotationItemRepository.findByQuotationIdInWithReorderDetails(quotationIds);

        for (QuotationItem item : items) {
            if (item.isFulfillmentCut()) continue;

            Bid winningBid = item.getWinningBid();
            if (winningBid == null) continue;

            Quotation quotation = item.getQuotation();
            LocalDateTime closedAt = quotation.getUpdatedAt();

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

        rows.sort(Comparator.comparing(ReorderPointRow::reorderDate));
        return rows;
    }

    @Transactional(readOnly = true)
    public PagedResponse<QuotationReportRow> getMyBidsForQuotation(Long quotationId, Long supplierId, Long authenticatedRepresentativeId, int page, int size) {
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

        int totalElements = rows.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<QuotationReportRow> content = rows.subList(fromIndex, toIndex);

        return new PagedResponse<>(content, page, size, totalElements, totalPages);
    }

    // "Não Cotar" — pra quando o representante abre uma cotação e não tem nenhum produto
    // pra ofertar. Idempotente (clicar duas vezes não duplica nem dá erro) e bloqueia se
    // já existir algum lance desse fornecedor nessa cotação — não faz sentido "declinar"
    // depois de já ter enviado preço pra pelo menos um item.
    @Transactional
    public void declineQuotation(Long quotationId, Long supplierId, Long authenticatedRepresentativeId, String impersonatedBy) {
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

        // Só grava evento quando foi via impersonação — declínio normal do representante
        // não precisa aparecer no "Ver Histórico" (esse timeline é focado em marcos da
        // cotação em si, não em cada ação de fornecedor); a exceção é justamente pra
        // deixar rastro de que foi um admin agindo em nome dele.
        if (impersonatedBy != null && !impersonatedBy.isBlank()) {
            logEvent(quotation, QuotationEventType.DECLINED,
                    "Cotação recusada (\"Não Cotar\") em nome de " + supplier.getName() + ".",
                    impersonatedBy + " (via \"Ver como\" o representante)");
        }
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
    public void finalizeFulfillment(Long quotationId, Long supplierId, Long authenticatedRepresentativeId, String impersonatedBy) {
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

        // Mesmo raciocínio do declineQuotation: só grava evento no "Ver Histórico" quando
        // foi via impersonação — confirmação normal do representante não precisa aparecer
        // ali, a exceção é justamente pra deixar rastro de admin agindo em nome dele.
        if (impersonatedBy != null && !impersonatedBy.isBlank()) {
            logEvent(quotation, QuotationEventType.FULFILLMENT_CONFIRMED,
                    "Atendimento do pedido confirmado em nome de " + supplier.getName() + ".",
                    impersonatedBy + " (via \"Ver como\" o representante)");
        }
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

    // Histórico/timeline da cotação — lista de eventos gravados por logEvent() em cada
    // marco importante do ciclo de vida, do mais antigo pro mais recente.
    @Transactional(readOnly = true)
    public List<QuotationEventResponse> getEvents(Long quotationId) {
        return quotationEventRepository.findByQuotationIdOrderByOccurredAtAsc(quotationId).stream()
                .map(e -> new QuotationEventResponse(e.getId(), e.getType(), e.getDescription(), e.getOccurredAt(), e.getPerformedBy()))
                .toList();
    }

    private void logEvent(Quotation quotation, QuotationEventType type, String description) {
        logEvent(quotation, type, description, null);
    }

    // performedBy = e-mail de quem disparou a ação (vem do AuthPrincipal, via
    // controller) — null pra eventos sem admin por trás (lembrete automático do
    // scheduler, lance recebido de representante).
    private void logEvent(Quotation quotation, QuotationEventType type, String description, String performedBy) {
        quotationEventRepository.save(QuotationEvent.builder()
                .quotation(quotation)
                .type(type)
                .description(description)
                .performedBy(performedBy)
                .build());
    }

    private String fmtEvent(LocalDateTime date) {
        if (date == null) return "—";
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"));
    }

    private QuotationResponse toResponse(Quotation quotation) {
        // 1 EXISTS aceitável aqui — esse overload só é usado nos pontos que tratam UMA
        // cotação por vez (create, update, publish, close...), nunca dentro de um loop.
        // findAll() usa o outro overload abaixo, com o hasBids já calculado em lote.
        return toResponse(quotation, bidRepository.existsByQuotationItem_QuotationId(quotation.getId()));
    }

    private QuotationResponse toResponse(Quotation quotation, boolean hasBids) {
        SupplierGroup group = safeGetSupplierGroup(quotation);
        List<Long> extraSupplierIds = quotation.getExtraSuppliers().stream()
                .map(Supplier::getId)
                .toList();
        List<String> extraSupplierNames = quotation.getExtraSuppliers().stream()
                .map(Supplier::getName)
                .toList();
        return new QuotationResponse(
                quotation.getId(),
                quotation.getName(),
                quotation.getStatus(),
                group != null ? group.getId() : null,
                group != null ? group.getName() : null,
                extraSupplierIds,
                extraSupplierNames,
                quotation.getCreatedAt(),
                quotation.getPublishedAt(),
                quotation.getExpirationDate(),
                quotation.getUpdatedAt(),
                quotation.getDefaultSalesProjectionDays(),
                hasBids
        );
    }

    // Um representante pode ser dono de mais de um fornecedor do mesmo grupo — sem essa
    // deduplicação explícita por ID, ele receberia um e-mail POR fornecedor que
    // representa, em vez de um só. .distinct() puro (igualdade de referência de objeto)
    // até funcionaria na prática aqui dentro da mesma transação, mas depende de um
    // detalhe interno do Hibernate (cache de 1º nível) — deduplicar pelo ID é
    // inequívoco e não depende de como a entidade foi carregada.
    private List<Representative> distinctById(List<Representative> reps) {
        Map<Long, Representative> byId = new LinkedHashMap<>();
        for (Representative rep : reps) {
            byId.putIfAbsent(rep.getId(), rep);
        }
        return new ArrayList<>(byId.values());
    }

    // O SupplierGroup tem @SQLRestriction("deleted = false"), que filtra a linha até na
    // hora de resolver a referência preguiçosa (lazy) vinda de uma Quotation antiga —
    // não só nas listagens normais. Isso significa que, se o grupo de uma cotação for
    // desativado depois que ela já existia, quotation.getSupplierGroup() lança
    // EntityNotFoundException em vez de simplesmente não achar nada. Em todo lugar
    // abaixo que só precisa LER o grupo (exibir nome, checar elegibilidade, notificar),
    // trata esse caso como "sem grupo" em vez de derrubar a operação inteira.
    private SupplierGroup safeGetSupplierGroup(Quotation quotation) {
        try {
            return quotation.getSupplierGroup();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    // Fornecedor "elegível" pra uma cotação é a UNIÃO de duas fontes, que passaram a
    // coexistir nesta versão: quem está no grupo da cotação (como sempre foi) + quem foi
    // adicionado avulso (extraSuppliers), pra cotação que não quer amarrar em grupo
    // nenhum ou quer complementar o grupo com um fornecedor específico de fora dele.
    // Esse é o ÚNICO lugar que resolve essa união — todo o resto do arquivo (checagem de
    // acesso do representante, e-mails de publicação/prorrogação/fechamento/lembrete,
    // fotografia de elegibilidade, taxa de resposta) chama esse método em vez de repetir
    // supplierRepository.findByGroup(group) cru, senão bastaria esquecer UM lugar pra
    // criar uma inconsistência (representante de fornecedor avulso vendo e-mail mas não
    // conseguindo abrir a cotação, ou vice-versa). LinkedHashSet só pra deduplicar um
    // fornecedor que por acaso esteja no grupo E também tenha sido adicionado avulso.
    private List<Supplier> getEligibleSuppliers(Quotation quotation) {
        Set<Supplier> result = new LinkedHashSet<>();
        SupplierGroup group = safeGetSupplierGroup(quotation);
        if (group != null) {
            result.addAll(supplierRepository.findByGroup(group));
        }
        result.addAll(quotation.getExtraSuppliers());
        return new ArrayList<>(result);
    }

    // Mesma união de cima, mas na direção oposta: "esse fornecedor específico está
    // elegível pra essa cotação?" — usado no relatório de desempenho do representante,
    // que já parte do fornecedor e precisa filtrar cotações, não o contrário.
    private boolean isSupplierEligibleForQuotation(Quotation quotation, Supplier supplier) {
        SupplierGroup group = safeGetSupplierGroup(quotation);
        if (group != null && supplier.getGroups().stream().anyMatch(g -> g.getId().equals(group.getId()))) {
            return true;
        }
        return quotation.getExtraSuppliers().stream().anyMatch(s -> s.getId().equals(supplier.getId()));
    }

    // Resolve a lista de ids (do request) pra entidades de verdade — mesmo padrão de
    // resolveSupplierGroup logo acima: ids inválidos derrubam a operação com 404 em vez
    // de serem ignorados silenciosamente (evita salvar uma cotação achando que tem um
    // fornecedor avulso que na real nunca foi resolvido). null ou lista vazia = sem
    // fornecedor avulso nenhum, que é o comportamento de sempre (só grupo).
    private Set<Supplier> resolveExtraSuppliers(List<Long> extraSupplierIds) {
        if (extraSupplierIds == null || extraSupplierIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<Supplier> result = new LinkedHashSet<>();
        for (Long id : extraSupplierIds) {
            result.add(supplierRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + id)));
        }
        return result;
    }

    // A tela força o admin a escolher UM jeito de destinar a cotação — Grupo OU
    // Representantes (fornecedores avulsos), nunca os dois ao mesmo tempo — pra não
    // criar ambiguidade sobre "por que esse fornecedor recebeu, ele tava no grupo ou
    // foi selecionado manualmente?". A validação vive aqui, não só no frontend, porque
    // create/update também são alcançáveis por outros caminhos (import, API direta).
    private void validateGroupOrExtraSuppliersExclusive(Long supplierGroupId, List<Long> extraSupplierIds) {
        boolean hasGroup = supplierGroupId != null;
        boolean hasExtraSuppliers = extraSupplierIds != null && !extraSupplierIds.isEmpty();
        if (hasGroup && hasExtraSuppliers) {
            throw new BusinessRuleException(
                    "Escolha Grupo OU Representantes pra essa cotação, não os dois — remova um antes de salvar.");
        }
    }

    private QuotationItemResponse toItemResponse(QuotationItem item) {
        return toItemResponse(item, null);
    }

    private QuotationItemResponse toItemResponse(QuotationItem item, Bid myBid) {
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
                effective,
                myBid != null ? myBid.getId() : null,
                myBid != null ? myBid.getValue() : null,
                item.getCostPrice()
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