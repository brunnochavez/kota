package com.bruno.kota.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.bruno.kota.entities.QuotationEligibleSupplier;

public interface QuotationEligibleSupplierRepository extends JpaRepository<QuotationEligibleSupplier, Long> {

    // Republicar reabre um novo "ciclo de resposta" pra mesma cotação — se o grupo
    // mudou entre a publicação original e a republicação, a fotografia precisa ser
    // substituída, não somada à antiga (senão um fornecedor que já saiu do grupo
    // continuaria contando como "elegível" pra sempre). Método derivado (Spring Data já
    // trata "deleteBy..." como delete em lote sozinho, sem precisar de @Modifying —
    // isso é só pra @Query customizada).
    void deleteByQuotationId(Long quotationId);

    // Usado pela tela de Estatísticas — todas as fotografias já tiradas, com
    // fornecedor e representante pré-carregados, sem filtro de período (mesmo
    // raciocínio das outras consultas "all details" usadas ali).
    @Query("SELECT e FROM QuotationEligibleSupplier e JOIN FETCH e.supplier LEFT JOIN FETCH e.representative")
    List<QuotationEligibleSupplier> findAllWithDetails();
}
