package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.bruno.kota.entities.PurchaseOrder;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    // JOIN FETCH em cotação e fornecedor — a listagem sempre mostra os dois nomes, sem
    // isso cada linha dispararia 2 queries lazy extras (N+1) só pra montar a tabela.
    @Query("SELECT po FROM PurchaseOrder po JOIN FETCH po.quotation JOIN FETCH po.supplier ORDER BY po.createdAt DESC")
    List<PurchaseOrder> findAllWithDetails();

    // Acha a OC de um fornecedor específico dentro de uma cotação específica — chave
    // única da tabela (ver @UniqueConstraint em PurchaseOrder), então nunca traz mais de
    // uma linha. Usado por findLastOrderForSupplierAndProduct pra achar status/prazo/nº
    // da OC depois de já ter achado QUAL cotação foi a última vez que esse fornecedor
    // ganhou esse produto.
    Optional<PurchaseOrder> findByQuotation_IdAndSupplier_Id(Long quotationId, Long supplierId);
}
