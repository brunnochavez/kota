package com.bruno.kota.services;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.BidAdminUpdateRequest;
import com.bruno.kota.dtos.BidRequest;
import com.bruno.kota.dtos.BidResponse;
import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.QuotationItemRepository;
import com.bruno.kota.repositories.QuotationRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.SupplierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRepository bidRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final QuotationRepository quotationRepository;
    private final SupplierRepository supplierRepository;
    private final RepresentativeRepository representativeRepository;

    @Transactional(readOnly = true)
    public List<BidResponse> findByQuotationItem(Long quotationItemId) {
        // findByQuotationItemIdWithDetails traz fornecedor e representante já
        // pré-carregados — antes, toResponse() acessava bid.getSupplier().getName() e
        // bid.getSubmittedBy().getName() lazy, 1 query extra de cada por lance.
        return bidRepository.findByQuotationItemIdWithDetails(quotationItemId).stream()
                .map(this::toResponse)
                .toList();
    }

    // Todos os lances de uma cotação inteira, numa query só — substitui o padrão que o
    // front usava antes (1 GET /bids?quotationItemId=X por item da cotação, em
    // paralelo) nas telas "Quem já respondeu" → "Ver" e "Revisar Lances Enviados". Numa
    // cotação com muitos itens (ex: 90), isso eram 90 requisições HTTP de uma vez só só
    // pra abrir um modal — reaproveita a mesma query já usada pelo Relatório de
    // Cotações (findByQuotationItem_QuotationIdInWithReportDetails), só que pra 1
    // cotação em vez de um lote.
    @Transactional(readOnly = true)
    public List<BidResponse> findByQuotation(Long quotationId) {
        return bidRepository.findByQuotationItem_QuotationIdInWithReportDetails(List.of(quotationId)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    // authenticatedRepresentativeId vem do token, não do corpo da requisição — é o que
    // fecha a brecha de um representante conseguir enviar lance em nome de outro só
    // mudando um número no JSON. null significa "quem chamou é admin", caso em que o
    // submittedById declarado no corpo ainda é respeitado (admin já tem acesso total).
    // impersonatedBy não é usado aqui dentro — o registro de auditoria da impersonação
    // agora acontece uma vez só, em QuotationService.logBidSubmission(), depois que
    // todos os itens da leva terminam de salvar. Mantido no parâmetro só pra não mudar a
    // assinatura que o controller já chama.
    public BidResponse submit(BidRequest request, Long authenticatedRepresentativeId, String impersonatedBy) {
        QuotationItem quotationItem = quotationItemRepository.findById(request.quotationItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item de cotação não encontrado: id " + request.quotationItemId()));

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + request.supplierId()));

        Long submittedById = authenticatedRepresentativeId != null ? authenticatedRepresentativeId : request.submittedById();
        Representative submittedBy = representativeRepository.findById(submittedById)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + submittedById));

        validateWindow(quotationItem);
        validateGroupAccess(quotationItem, supplier);
        validateSubmitter(supplier, submittedBy);

        Bid bid = bidRepository.findByQuotationItemIdAndSupplierId(quotationItem.getId(), supplier.getId())
                .orElseGet(() -> Bid.builder()
                        .quotationItem(quotationItem)
                        .supplier(supplier)
                        .build());

        bid.setSubmittedBy(submittedBy);
        bid.setValue(request.value());
        bid.setDeliveryDeadlineDays(request.deliveryDeadlineDays());
        bid.setNotes(request.notes());

        Bid saved = bidRepository.save(bid);
        // Não loga evento aqui — antes gravava 1 "LANCE RECEBIDO" por item, o que
        // enchia o histórico de dezenas de linhas idênticas quando um representante
        // enviava uma cotação com muitos produtos de uma vez. O registro agora é 1 só
        // por envio, feito pelo QuotationService.logBidSubmission() depois que TODOS os
        // itens da leva terminam de salvar (ver submitAllBids() no representante.html).

        return toResponse(saved);
    }

    // Edição/exclusão feita pelo ADMIN (não pelo representante) — usada na tela de revisão
    // pra corrigir um lance antes de confirmar o fechamento. Se a cotação estava em
    // REVIEWING, qualquer mudança aqui desfaz os vencedores calculados e volta pra Disponível,
    // porque o total pode ter mudado.
    @Transactional
    public BidResponse updateByAdmin(Long bidId, BidAdminUpdateRequest request) {
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new ResourceNotFoundException("Lance não encontrado: id " + bidId));

        Quotation quotation = bid.getQuotationItem().getQuotation();
        ensureBidEditable(quotation);
        invalidateReviewIfNeeded(quotation);

        bid.setValue(request.value());
        bid.setDeliveryDeadlineDays(request.deliveryDeadlineDays());
        return toResponse(bidRepository.save(bid));
    }

    // reassignToRunnerUp = true → em vez de deixar o item sem vencedor, atribui pro
    // fornecedor com o 2º menor preço (o menor entre os lances QUE SOBRAM depois de
    // excluir este) — evita o passo extra de ir em "Adicionar produto a esse
    // representante" reatribuir manualmente. Se não sobrar nenhum outro lance pra esse
    // item, o resultado é o mesmo de reassignToRunnerUp = false (fica sem vencedor,
    // porque não tem pra quem reatribuir).
    @Transactional
    public void deleteByAdmin(Long bidId, boolean reassignToRunnerUp) {
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new ResourceNotFoundException("Lance não encontrado: id " + bidId));

        QuotationItem item = bid.getQuotationItem();
        Quotation quotation = item.getQuotation();
        ensureBidEditable(quotation);

        boolean wasWinner = item.getWinningBid() != null && item.getWinningBid().getId().equals(bidId);
        if (wasWinner) {
            Bid runnerUp = reassignToRunnerUp
                    ? bidRepository.findByQuotationItemIdWithDetails(item.getId()).stream()
                            .filter(b -> !b.getId().equals(bidId))
                            .min(Comparator.comparing(Bid::getValue))
                            .orElse(null)
                    : null;
            item.setWinningBid(runnerUp);
            quotationItemRepository.save(item);
        }

        bidRepository.delete(bid);
    }

    private void ensureBidEditable(Quotation quotation) {
        if (quotation.getStatus() == QuotationStatus.CLOSED) {
            throw new BusinessRuleException("Não é possível editar ou excluir lances de uma cotação já fechada.");
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

    private void validateWindow(QuotationItem quotationItem) {
        Quotation quotation = quotationItem.getQuotation();

        if (quotation.getStatus() != QuotationStatus.AVAILABLE) {
            throw new BusinessRuleException("Esta cotação não está disponível para receber lances.");
        }
        if (quotation.getExpirationDate() == null || !LocalDateTime.now().isBefore(quotation.getExpirationDate())) {
            throw new BusinessRuleException("O prazo desta cotação já expirou.");
        }
    }

    // Elegível = pertence ao grupo da cotação OU foi adicionado avulso como
    // Representante (ver Quotation.extraSuppliers) — mesma união usada em
    // QuotationService.getEligibleSuppliers(). Antes só checava o grupo; um fornecedor
    // adicionado como Representante avulso passava em validateRepresentativeCanViewQuotation
    // (que já usava a união) mas travava aqui na hora de enviar o lance de verdade,
    // porque essa checagem tinha sua própria cópia da regra, desatualizada.
    private void validateGroupAccess(QuotationItem quotationItem, Supplier supplier) {
        Quotation quotation = quotationItem.getQuotation();
        SupplierGroup group = safeGetSupplierGroup(quotation);
        boolean inGroup = group != null && supplier.getGroups().contains(group);
        boolean isExtraSupplier = quotation.getExtraSuppliers().stream()
                .anyMatch(s -> s.getId().equals(supplier.getId()));
        if (!inGroup && !isExtraSupplier) {
            throw new BusinessRuleException("Este fornecedor não está autorizado a responder esta cotação.");
        }
    }

    // O SupplierGroup tem @SQLRestriction("deleted = false"), que filtra a linha até na
    // hora de resolver a referência preguiçosa (lazy) vinda de uma Quotation antiga. Se
    // o grupo dessa cotação foi desativado nesse meio-tempo, trata como "sem grupo" —
    // o resultado prático é bloquear o lance com a mensagem de negócio de cima, em vez
    // de uma exceção não tratada.
    private SupplierGroup safeGetSupplierGroup(Quotation quotation) {
        try {
            return quotation.getSupplierGroup();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    private void validateSubmitter(Supplier supplier, Representative submittedBy) {
        if (supplier.getRepresentative() == null || !supplier.getRepresentative().getId().equals(submittedBy.getId())) {
            throw new BusinessRuleException("Este representante não está autorizado a enviar lances em nome desse fornecedor.");
        }
    }

    private BidResponse toResponse(Bid bid) {
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