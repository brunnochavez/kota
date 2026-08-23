package com.bruno.kota.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

// Índice em "status": ao contrário das colunas de FK (que o MySQL já indexa sozinho
// junto com a constraint), "status" não é FK — e é filtrada o tempo todo (findByStatus
// no dashboard, nos relatórios, na checagem de expiração). Sem índice, cada uma dessas
// consultas faz table scan completo em "quotations"; hoje com poucas linhas isso não
// dói, mas cresce proporcional ao histórico. hibernate.ddl-auto=update cria esse índice
// automaticamente no próximo deploy, sem exigir migração manual.
@Entity
@Table(name = "quotations", indexes = @Index(name = "idx_quotations_status", columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Nome da cotação é obrigatório")
    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_group_id")
    private SupplierGroup supplierGroup;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "expiration_date")
    private LocalDateTime expirationDate;

    // Padrão aplicado a todo item novo da cotação (herdado enquanto o item não tiver
    // sobrescrita própria — ver QuotationItem.salesProjectionDaysOverride). Puramente
    // informativo/planejamento pro admin, não interfere em nada do fluxo de lance/fechamento.
    @Column(name = "default_sales_projection_days")
    private Integer defaultSalesProjectionDays;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QuotationStatus status = QuotationStatus.DRAFT;

    // Marca quando o lembrete de prazo já foi disparado pra essa cotação — sem isso, o
    // job que roda a cada minuto (QuotationReminderScheduler) mandaria o mesmo e-mail de
    // novo a cada execução, pra sempre que a cotação continuasse dentro da janela de
    // lembrete. null = ainda não mandou; preenchido = já mandou, não manda de novo pra
    // essa cotação (mesmo que ela seja reaberta/editada depois).
    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;
}