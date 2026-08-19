package com.bruno.kota.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
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

// "Não Cotar" — o representante olhou a cotação inteira e não tem nenhum produto pra
// ofertar. Diferente de simplesmente ignorar a cotação (que fica indistinguível de
// "esqueceu" ou "nem viu"), isso é uma resposta explícita, e por isso CONTA como
// resposta na taxa de participação — só não tem preço nenhum associado, porque não
// existe.
@Entity
@Table(
        name = "quotation_declines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_decline_quotation_supplier",
                columnNames = {"quotation_id", "supplier_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationDecline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declined_by_id", nullable = false)
    private Representative declinedBy;

    @Column(name = "declined_at", nullable = false)
    private LocalDateTime declinedAt;
}
