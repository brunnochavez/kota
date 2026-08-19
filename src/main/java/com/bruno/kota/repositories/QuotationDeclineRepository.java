package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.QuotationDecline;

public interface QuotationDeclineRepository extends JpaRepository<QuotationDecline, Long> {

    List<QuotationDecline> findByQuotationId(Long quotationId);

    boolean existsByQuotationIdAndSupplierId(Long quotationId, Long supplierId);
}
