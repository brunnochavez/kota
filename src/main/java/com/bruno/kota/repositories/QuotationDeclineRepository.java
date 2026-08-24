package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.QuotationDecline;

public interface QuotationDeclineRepository extends JpaRepository<QuotationDecline, Long> {

    List<QuotationDecline> findByQuotationId(Long quotationId);

    boolean existsByQuotationIdAndSupplierId(Long quotationId, Long supplierId);

    boolean existsBySupplierId(Long supplierId);

    boolean existsByDeclinedById(Long representativeId);

    // Usado pela Taxa de Preenchimento — todas as declarações de "Não Cotar" de um lote
    // de cotações numa query só, já com quem declarou pré-carregado. Substitui o antigo
    // findByQuotationId dentro do loop por cotação (N+1).
    @Query("SELECT d FROM QuotationDecline d "
            + "JOIN FETCH d.quotation "
            + "JOIN FETCH d.declinedBy "
            + "WHERE d.quotation.id IN :quotationIds")
    List<QuotationDecline> findByQuotationIdInWithDetails(@Param("quotationIds") List<Long> quotationIds);

    // Usado pela tela de Estatísticas — todas as recusas já registradas, com fornecedor
    // e representante pré-carregados, sem filtro de período (mesmo raciocínio de
    // BidRepository.findAllWithFullDetails).
    @Query("SELECT d FROM QuotationDecline d JOIN FETCH d.supplier JOIN FETCH d.declinedBy")
    List<QuotationDecline> findAllWithDetails();
}
