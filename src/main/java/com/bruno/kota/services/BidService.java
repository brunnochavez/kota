package com.bruno.kota.services;

import java.time.LocalDateTime;
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
        return bidRepository.findByQuotationItemId(quotationItemId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    // authenticatedRepresentativeId vem do token, não do corpo da requisição — é o que
    // fecha a brecha de um representante conseguir enviar lance em nome de outro só
    // mudando um número no JSON. null significa "quem chamou é admin", caso em que o
    // submittedById declarado no corpo ainda é respeitado (admin já tem acesso total).
    public BidResponse submit(BidRequest request, Long authenticatedRepresentativeId) {
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

        return toResponse(bidRepository.save(bid));
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

    @Transactional
    public void deleteByAdmin(Long bidId) {
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new ResourceNotFoundException("Lance não encontrado: id " + bidId));

        QuotationItem item = bid.getQuotationItem();
        Quotation quotation = item.getQuotation();
        ensureBidEditable(quotation);

        // Se esse lance era o vencedor do item, o item fica sem vencedor — só ele, não a
        // cotação inteira. O admin resolve depois, ainda dentro da revisão, atribuindo um
        // vencedor pra esse item em "Adicionar produto a esse representante" (funciona pra
        // reatribuir, não só pra produto novo — reaproveita o item existente via assign-winner).
        if (item.getWinningBid() != null && item.getWinningBid().getId().equals(bidId)) {
            item.setWinningBid(null);
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

    private void validateGroupAccess(QuotationItem quotationItem, Supplier supplier) {
        SupplierGroup group = safeGetSupplierGroup(quotationItem.getQuotation());
        if (group == null || !supplier.getGroups().contains(group)) {
            throw new BusinessRuleException("Este fornecedor não pertence ao grupo autorizado a responder esta cotação.");
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