package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.QuotationEvent;

public interface QuotationEventRepository extends JpaRepository<QuotationEvent, Long> {

    List<QuotationEvent> findByQuotationIdOrderByOccurredAtAsc(Long quotationId);
}
