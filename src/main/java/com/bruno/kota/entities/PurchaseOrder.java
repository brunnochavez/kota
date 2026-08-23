package com.bruno.kota.entities;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// Gerada automaticamente (uma por fornecedor com item ganho) assim que uma cotação é
// confirmada como CLOSED — é o documento formal do pedido, com número próprio (o
// próprio id, exibido como "OC-000123" no frontend, mesmo padrão de
// formatQuotationNumber), diferente do OrderFulfillmentConfirmation (que é o
// fornecedor dizendo "revisei e finalizei meu pedido") e do PDF de resultado (que é só
// a listagem de quem ganhou o quê). totalValue é congelado no momento da criação —
// mesmo que um item seja cortado por falta de estoque depois, a OC continua
// registrando o valor do pedido como foi fechado originalmente.
@Entity
@Table(name = "purchase_orders",
        uniqueConstraints = @UniqueConstraint(columnNames = {"quotation_id", "supplier_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrder {

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
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Estimativa: data de criação + o MAIOR prazo de entrega entre os itens ganhos por
    // esse fornecedor nessa cotação (pior caso — a OC só está de verdade completa
    // quando o último item também chegar). Prazo de entrega vem do lance vencedor de
    // cada item (ou o padrão do fornecedor, quando o lance não tem prazo próprio) —
    // mesma fonte de dado já usada no relatório de Ponto de Compra.
    @Column(name = "estimated_delivery_date")
    private LocalDateTime estimatedDeliveryDate;

    @Column(name = "total_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PurchaseOrderStatus status = PurchaseOrderStatus.PENDING;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;
}
