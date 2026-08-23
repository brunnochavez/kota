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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Um registro por marco importante do ciclo de vida da cotação — criada, publicada,
// lance recebido, lembrete enviado, prazo prorrogado, fechada. "description" já vem
// pronto (texto formatado) de quem grava o evento, no momento em que ele acontece —
// evita esse serviço precisar remontar frases a partir de outras tabelas toda vez que
// alguém pede o histórico, e evita quebrar textos antigos se a regra de formatação
// mudar no futuro (o texto gravado é o que aconteceu NAQUELE momento).
@Entity
@Table(name = "quotation_events", indexes = @Index(name = "idx_quotation_events_quotation", columnList = "quotation_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuotationEventType type;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
