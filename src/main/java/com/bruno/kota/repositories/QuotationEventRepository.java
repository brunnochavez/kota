package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.QuotationEvent;

public interface QuotationEventRepository extends JpaRepository<QuotationEvent, Long> {

    List<QuotationEvent> findByQuotationIdOrderByOccurredAtAsc(Long quotationId);

    // Usado só na exclusão definitiva de uma cotação em Rascunho (ver
    // QuotationService.delete()) — toda cotação já nasce com pelo menos um evento
    // (CREATED), e quotation_id é NOT NULL aqui, então excluir a cotação sem limpar os
    // eventos primeiro derruba com violação de chave estrangeira.
    void deleteByQuotationId(Long quotationId);
}
