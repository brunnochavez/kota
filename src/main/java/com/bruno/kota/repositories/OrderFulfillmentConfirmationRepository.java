package com.bruno.kota.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.OrderFulfillmentConfirmation;

public interface OrderFulfillmentConfirmationRepository extends JpaRepository<OrderFulfillmentConfirmation, Long> {
    Optional<OrderFulfillmentConfirmation> findByQuotationIdAndSupplierId(Long quotationId, Long supplierId);

    boolean existsByQuotationIdAndSupplierId(Long quotationId, Long supplierId);

    boolean existsBySupplierId(Long supplierId);
}
