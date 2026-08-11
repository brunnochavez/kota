package com.bruno.kota.repositories;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationStatus;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    List<Quotation> findByStatusAndExpirationDateBefore(QuotationStatus status, LocalDateTime dateTime);
}