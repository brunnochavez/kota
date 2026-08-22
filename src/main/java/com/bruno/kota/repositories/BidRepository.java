package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.Bid;

public interface BidRepository extends JpaRepository<Bid, Long> {
    List<Bid> findByQuotationItemId(Long quotationItemId);

    // Usado pela tela de revisão de lances (por item) — sem isso, bid.getSupplier().getName()
    // e bid.getSubmittedBy().getName() no mapeamento disparavam 1 query lazy cada, por lance.
    @Query("SELECT b FROM Bid b JOIN FETCH b.supplier LEFT JOIN FETCH b.submittedBy "
            + "WHERE b.quotationItem.id = :quotationItemId")
    List<Bid> findByQuotationItemIdWithDetails(@Param("quotationItemId") Long quotationItemId);

    Optional<Bid> findByQuotationItemIdAndSupplierId(Long quotationItemId, Long supplierId);

    List<Bid> findByQuotationItem_QuotationId(Long quotationId);

    List<Bid> findBySupplierId(Long supplierId);

    boolean existsBySupplierId(Long supplierId);

    boolean existsBySubmittedById(Long representativeId);

    // Usado por getRepresentativePerformance — antes bidRepository.findBySupplierId sozinho
    // deixava item/produto/cotação/vencedor todos lazy, e o loop acessava cada um por
    // lance (N+1 por lance do fornecedor no período). Agora vem tudo pré-carregado.
    @Query("SELECT b FROM Bid b "
            + "JOIN FETCH b.quotationItem qi "
            + "JOIN FETCH qi.product "
            + "JOIN FETCH qi.quotation q "
            + "LEFT JOIN FETCH q.supplierGroup "
            + "LEFT JOIN FETCH qi.winningBid "
            + "WHERE b.supplier.id = :supplierId")
    List<Bid> findBySupplierIdWithDetails(@Param("supplierId") Long supplierId);

    // Usado pelo Relatório de Cotações — todos os lances de um lote de cotações numa
    // query só, já com item/produto/cotação/fornecedor/representante pré-carregados.
    // Substitui o antigo findByQuotationItem_QuotationId chamado dentro de um loop por
    // cotação (N+1), cujo próprio acesso a bid.getSupplier()/getSubmittedBy() dentro do
    // loop disparava mais uma query por lance (N+1 dentro do N+1).
    @Query("SELECT b FROM Bid b "
            + "JOIN FETCH b.quotationItem qi "
            + "JOIN FETCH qi.product "
            + "JOIN FETCH qi.quotation "
            + "LEFT JOIN FETCH qi.winningBid "
            + "JOIN FETCH b.supplier "
            + "LEFT JOIN FETCH b.submittedBy "
            + "WHERE qi.quotation.id IN :quotationIds")
    List<Bid> findByQuotationItem_QuotationIdInWithReportDetails(@Param("quotationIds") List<Long> quotationIds);

    // Usado pela Taxa de Preenchimento — mesma ideia, mas só precisa saber "quem
    // respondeu em qual cotação", então traz só o representante e a cotação.
    @Query("SELECT b FROM Bid b "
            + "JOIN FETCH b.quotationItem qi "
            + "JOIN FETCH qi.quotation "
            + "JOIN FETCH b.submittedBy "
            + "WHERE qi.quotation.id IN :quotationIds")
    List<Bid> findByQuotationItem_QuotationIdInWithSubmitter(@Param("quotationIds") List<Long> quotationIds);

    // Usado por toResponse(Quotation) — pra distinguir, no frontend, uma cotação que
    // expirou SEM ninguém responder (nada a fazer) de uma que expirou COM lances
    // pendentes de fechamento (ainda dá pra calcular vencedores). 1 EXISTS simples,
    // aceitável nos pontos que tratam 1 cotação por vez (não é loop).
    boolean existsByQuotationItem_QuotationId(Long quotationId);

    // Usado por findAll() — em vez de 1 existsByQuotationItem_QuotationId() por cotação
    // dentro do loop, traz de uma vez só os ids de cotação que têm pelo menos 1 lance,
    // dentre um lote de cotações.
    @Query("SELECT DISTINCT qi.quotation.id FROM Bid b JOIN b.quotationItem qi WHERE qi.quotation.id IN :quotationIds")
    List<Long> findDistinctQuotationIdsWithBids(@Param("quotationIds") List<Long> quotationIds);
}