package com.bruno.kota.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.PurchaseOrderResponse;
import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.PurchaseOrder;
import com.bruno.kota.entities.PurchaseOrderStatus;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.PurchaseOrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;

    // Chamado por QuotationService.confirmClose() logo depois da cotação virar CLOSED —
    // uma Ordem de Compra por fornecedor que ganhou pelo menos um item, nunca uma por
    // item. "items" já vem com winningBid resolvido (é a mesma lista usada pra montar
    // o PDF de resultado e pra notificar os representantes).
    @Transactional
    public void createForClosedQuotation(Quotation quotation, List<QuotationItem> items) {
        Map<Long, Supplier> suppliersById = new LinkedHashMap<>();
        Map<Long, List<QuotationItem>> itemsBySupplier = new LinkedHashMap<>();

        for (QuotationItem item : items) {
            Bid winner = item.getWinningBid();
            if (winner == null) {
                continue;
            }
            Supplier supplier = winner.getSupplier();
            suppliersById.putIfAbsent(supplier.getId(), supplier);
            itemsBySupplier.computeIfAbsent(supplier.getId(), k -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<Long, List<QuotationItem>> entry : itemsBySupplier.entrySet()) {
            Supplier supplier = suppliersById.get(entry.getKey());
            List<QuotationItem> supplierItems = entry.getValue();

            BigDecimal total = BigDecimal.ZERO;
            Integer maxDeliveryDeadlineDays = null;
            for (QuotationItem item : supplierItems) {
                Bid winner = item.getWinningBid();
                total = total.add(winner.getValue().multiply(item.getQuantity()));

                Integer deliveryDays = winner.getDeliveryDeadlineDays() != null
                        ? winner.getDeliveryDeadlineDays()
                        : supplier.getDefaultDeliveryDeadlineDays();
                if (deliveryDays != null && (maxDeliveryDeadlineDays == null || deliveryDays > maxDeliveryDeadlineDays)) {
                    maxDeliveryDeadlineDays = deliveryDays;
                }
            }

            LocalDateTime now = LocalDateTime.now();
            purchaseOrderRepository.save(PurchaseOrder.builder()
                    .quotation(quotation)
                    .supplier(supplier)
                    .createdAt(now)
                    .estimatedDeliveryDate(maxDeliveryDeadlineDays != null ? now.plusDays(maxDeliveryDeadlineDays) : null)
                    .totalValue(total)
                    .status(PurchaseOrderStatus.PENDING)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderResponse> findAll() {
        return purchaseOrderRepository.findAllWithDetails().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrder findEntityById(Long id) {
        return purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ordem de compra não encontrada: id " + id));
    }

    // Idempotente de propósito, mesmo padrão de finalizeFulfillment — clicar duas
    // vezes (duplo toque, rede lenta) não deve dar erro nem sobrescrever receivedAt.
    @Transactional
    public PurchaseOrderResponse markReceived(Long id) {
        PurchaseOrder order = findEntityById(id);
        if (order.getStatus() == PurchaseOrderStatus.RECEIVED) {
            return toResponse(order);
        }
        order.setStatus(PurchaseOrderStatus.RECEIVED);
        order.setReceivedAt(LocalDateTime.now());
        return toResponse(purchaseOrderRepository.save(order));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder order) {
        return new PurchaseOrderResponse(
                order.getId(),
                order.getQuotation().getId(),
                order.getQuotation().getName(),
                order.getSupplier().getId(),
                order.getSupplier().getName(),
                order.getCreatedAt(),
                order.getEstimatedDeliveryDate(),
                order.getTotalValue(),
                order.getStatus(),
                order.getReceivedAt()
        );
    }
}
