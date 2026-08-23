package com.bruno.kota.repositories;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationStatus;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    List<Quotation> findByStatusAndExpirationDateBefore(QuotationStatus status, LocalDateTime dateTime);

    List<Quotation> findByStatus(QuotationStatus status);

    // Usado pela Taxa de Preenchimento — traz o grupo de fornecedores junto (LEFT JOIN
    // FETCH), evitando uma query extra por cotação só pra ler quotation.getSupplierGroup().
    // LEFT JOIN em vez de INNER: cotação sem grupo, ou com grupo apagado de vez (hard
    // delete), continua aparecendo na lista, só com grupo nulo — mesmo comportamento que
    // safeGetSupplierGroup() já tratava via try/catch antes.
    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.supplierGroup WHERE q.status = :status")
    List<Quotation> findByStatusWithGroup(@Param("status") QuotationStatus status);

    // Usado pela listagem geral (/quotations, primeira chamada do dashboard) — evita 1
    // query por cotação só pra ler o nome do grupo (quotation.getSupplierGroup() é lazy).
    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.supplierGroup")
    List<Quotation> findAllWithGroup();

    // Usado pelo desempenho do representante — antes era quotationRepository.findAll()
    // (tabela inteira, sem filtro nenhum) filtrado em memória depois. Agora o status e a
    // data de publicação já vêm filtrados pelo banco, e o grupo já vem junto.
    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.supplierGroup "
            + "WHERE q.status <> com.bruno.kota.entities.QuotationStatus.DRAFT "
            + "AND q.publishedAt IS NOT NULL AND q.publishedAt > :since")
    List<Quotation> findPublishedSince(@Param("since") LocalDateTime since);

    // Usado pelo lembrete de prazo (QuotationReminderScheduler) — cotações Disponíveis
    // cujo prazo cai dentro da janela de aviso (entre agora e "daqui a N horas") e que
    // ainda não tiveram lembrete disparado. reminderSentAt IS NULL garante que cada
    // cotação só entra nessa lista uma vez, mesmo com o job rodando a cada minuto.
    // LEFT JOIN FETCH no grupo, pelo mesmo motivo de sempre: quem chama essa lista
    // precisa saber quem são os fornecedores do grupo pra achar os representantes.
    @Query("SELECT q FROM Quotation q LEFT JOIN FETCH q.supplierGroup "
            + "WHERE q.status = com.bruno.kota.entities.QuotationStatus.AVAILABLE "
            + "AND q.reminderSentAt IS NULL "
            + "AND q.expirationDate IS NOT NULL "
            + "AND q.expirationDate BETWEEN :now AND :reminderThreshold")
    List<Quotation> findDueForReminder(@Param("now") LocalDateTime now, @Param("reminderThreshold") LocalDateTime reminderThreshold);
}