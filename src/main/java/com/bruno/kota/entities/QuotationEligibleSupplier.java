package com.bruno.kota.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Fotografia de "quem estava elegível a responder essa cotação", tirada no momento em
// que ela é publicada (ver QuotationService.snapshotEligibleSuppliers, chamado de
// publish() e republish()). Existe porque o grupo de fornecedores MUDA com o tempo
// (fornecedor entra/sai de grupo) — sem essa fotografia, "quem estava elegível na
// cotação #42 de 3 meses atrás" só dava pra responder com a composição ATUAL do grupo,
// que pode já ser bem diferente. representative aqui também é uma cópia do momento —
// guardado separado do supplier porque o vínculo fornecedor→representante também pode
// mudar depois, e a estatística é sobre quem era o representante DAQUELE fornecedor
// naquela hora, não o de hoje.
@Entity
@Table(name = "quotation_eligible_suppliers",
        uniqueConstraints = @UniqueConstraint(name = "uk_eligible_quotation_supplier", columnNames = {"quotation_id", "supplier_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationEligibleSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    // Nullable de propósito — um fornecedor pode estar no grupo sem representante
    // vinculado ainda; nesse caso ele conta como "elegível" pro ranking de fornecedor,
    // mas não entra no ranking de representante (que precisa de alguém pra atribuir).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "representative_id")
    private Representative representative;
}
