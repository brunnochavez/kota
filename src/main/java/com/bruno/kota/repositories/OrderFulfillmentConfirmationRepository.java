package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.OrderFulfillmentConfirmation;

public interface OrderFulfillmentConfirmationRepository extends JpaRepository<OrderFulfillmentConfirmation, Long> {
    Optional<OrderFulfillmentConfirmation> findByQuotationIdAndSupplierId(Long quotationId, Long supplierId);

    boolean existsByQuotationIdAndSupplierId(Long quotationId, Long supplierId);

    boolean existsBySupplierId(Long supplierId);

    // Usado por findFulfillmentSummaries — em vez de 1 exists() POR cotação fechada
    // dentro de um loop, traz de uma vez só os ids de cotação já finalizados por esse
    // fornecedor, dentre um lote de cotações. O chamador testa com .contains(quotationId).
    @Query("SELECT c.quotation.id FROM OrderFulfillmentConfirmation c "
            + "WHERE c.supplier.id = :supplierId AND c.quotation.id IN :quotationIds")
    List<Long> findQuotationIdsBySupplierIdAndQuotationIdIn(
            @Param("supplierId") Long supplierId, @Param("quotationIds") List<Long> quotationIds);
}
