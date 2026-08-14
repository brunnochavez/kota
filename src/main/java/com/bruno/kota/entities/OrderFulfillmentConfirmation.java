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
import org.hibernate.annotations.CreationTimestamp;

// Um registro aqui = "esse fornecedor já revisou os itens que ganhou nessa cotação e
// finalizou o pedido". Enquanto não existir, os itens ganhos aparecem em "Resultados de
// Cotações" (pendente); depois de criado, passam a aparecer em "Ganhei" (definitivo).
// Único por (quotation, supplier) — não dá pra finalizar duas vezes a mesma coisa.
@Entity
@Table(name = "order_fulfillment_confirmations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"quotation_id", "supplier_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderFulfillmentConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @CreationTimestamp
    @Column(name = "confirmed_at", updatable = false)
    private LocalDateTime confirmedAt;
}
