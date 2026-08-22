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

    // Usado por ProductService (tela de Produtos: busca paginada e lista de inativos) —
    // pra cada produto que já teve um item vencedor, o mapeamento pra resposta lê
    // item.getQuotation().getUpdatedAt() (pra achar o item MAIS recente) e depois
    // winningBid.getValue()/getSupplier().getName() (pro "último preço pago"). Sem JOIN
    // FETCH, cada um desses acessos lazy disparava uma query por produto com histórico —
    // numa lista de 50 produtos com preço, isso eram até 150 queries extras.
    @Query("SELECT qi FROM QuotationItem qi "
            + "JOIN FETCH qi.quotation "
            + "JOIN FETCH qi.winningBid wb "
            + "JOIN FETCH wb.supplier "
            + "WHERE qi.product.id IN :productIds AND qi.winningBid IS NOT NULL")
    List<QuotationItem> findAllWonByProductIds(@Param("productIds") List<Long> productIds);

    // Usado pelo relatório de Ponto de Compra — traz, numa query só, todos os itens de
    // um lote de cotações já com produto, lance vencedor, fornecedor e representante
    // pré-carregados (JOIN FETCH). Antes disso existia uma query de itens POR cotação
    // dentro de um loop (N+1), e cada acesso a produto/vencedor/fornecedor/representante
    // disparava outra query em cima dessa (efeito N+1 dentro do N+1).
    @Query("SELECT qi FROM QuotationItem qi "
            + "JOIN FETCH qi.quotation "
            + "JOIN FETCH qi.product "
            + "LEFT JOIN FETCH qi.winningBid wb "
            + "LEFT JOIN FETCH wb.supplier "
            + "LEFT JOIN FETCH wb.submittedBy "
            + "WHERE qi.quotation.id IN :quotationIds")
    List<QuotationItem> findByQuotationIdInWithReorderDetails(@Param("quotationIds") List<Long> quotationIds);
}