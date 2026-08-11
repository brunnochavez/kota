package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.QuotationItem;

public interface QuotationItemRepository extends JpaRepository<QuotationItem, Long> {
    List<QuotationItem> findByQuotationId(Long quotationId);

    @Query("SELECT qi FROM QuotationItem qi LEFT JOIN Bid b ON b.quotationItem = qi "
            + "WHERE qi.quotation.id = :quotationId AND b.id IS NULL")
    List<QuotationItem> findItemsWithNoBids(@Param("quotationId") Long quotationId);

    @Query("SELECT qi FROM QuotationItem qi WHERE qi.product.id = :productId AND qi.winningBid IS NOT NULL "
            + "ORDER BY qi.quotation.updatedAt DESC")
    List<QuotationItem> findLastWonByProductId(@Param("productId") Long productId);

    @Query("SELECT qi FROM QuotationItem qi WHERE qi.product.id IN :productIds AND qi.winningBid IS NOT NULL")
    List<QuotationItem> findAllWonByProductIds(@Param("productIds") List<Long> productIds);
}