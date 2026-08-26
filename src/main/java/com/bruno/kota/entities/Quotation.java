package com.bruno.kota.entities;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

// Índice composto em (status, expiration_date): ao contrário das colunas de FK (que o
// MySQL já indexa sozinho junto com a constraint), essas duas não são FK — e são
// filtradas JUNTAS o tempo todo (findByStatusAndExpirationDateBefore e
// findDueForReminder, chamadas pelo scheduler a cada execução, além do dashboard e da
// paginação por aba). Sem índice, cada uma dessas consultas faz table scan completo em
// "quotations"; hoje com poucas linhas isso não dói, mas cresce proporcional ao
// histórico. hibernate.ddl-auto=update cria esse índice automaticamente no próximo
// deploy, sem exigir migração manual.
@Entity
@Table(name = "quotations", indexes = @Index(name = "idx_quotations_status_expiration", columnList = "status, expiration_date"))
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

    // Fornecedores avulsos, adicionados diretamente à cotação — sem precisar pertencer
    // ao grupo (nem a cotação precisar ter grupo nenhum). Elegibilidade de verdade
    // (quem recebe e-mail, quem consegue ver/responder) é sempre a UNIÃO deste conjunto
    // com o grupo, resolvida em QuotationService.getEligibleSuppliers() — nunca leia
    // este campo sozinho fora de lá. Tabela de junção própria (não reaproveita a de
    // Supplier↔SupplierGroup) porque isso aqui é por COTAÇÃO, não por grupo.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "quotation_extra_suppliers",
            joinColumns = @JoinColumn(name = "quotation_id"),
            inverseJoinColumns = @JoinColumn(name = "supplier_id"))
    @Builder.Default
    private Set<Supplier> extraSuppliers = new LinkedHashSet<>();

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