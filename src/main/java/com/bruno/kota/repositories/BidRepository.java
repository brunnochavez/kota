package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.Bid;

public interface BidRepository extends JpaRepository<Bid, Long> {
    List<Bid> findByQuotationItemId(Long quotationItemId);

    Optional<Bid> findByQuotationItemIdAndSupplierId(Long quotationItemId, Long supplierId);
}