package com.bruno.kota.entities;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "quotation_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    // Preço de custo (opcional) importado junto com o item — só existe quando a planilha
    // tinha uma coluna de custo mapeada E o admin marcou "incluir preços de custo" no
    // momento da importação (ver QuotationImportService). Puramente informativo: usado
    // em "Revisar Lances Enviados" pra mostrar se o preço do lance vencedor representa
    // aumento ou baixa em relação ao que era pago antes — nunca interfere em cálculo de
    // vencedor, elegibilidade ou fechamento.
    @Column(name = "cost_price", precision = 12, scale = 4)
    private BigDecimal costPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winning_bid_id")
    private Bid winningBid;

    // Marcado true quando o representante confirma que não tem esse item em estoque, na
    // tela de "Resultados de Cotações". Só existe pra itens de cotação já FECHADA — não
    // significa "excluído da cotação" (isso é outra coisa, feito ainda em Rascunho/Revisão
    // via delete de verdade); aqui o item continua existindo, só marcado como não
    // entregável por esse fornecedor.
    @Column(nullable = false)
    @Builder.Default
    private boolean fulfillmentCut = false;

    // Sobrescrita individual da projeção de venda desse item — null significa "usa o
    // padrão da cotação" (Quotation.defaultSalesProjectionDays). Editável a qualquer
    // momento, mesmo com a cotação já fechada, porque é só anotação de planejamento do
    // admin, não afeta lance, vencedor nem fechamento.
    @Column(name = "sales_projection_days_override")
    private Integer salesProjectionDaysOverride;
}