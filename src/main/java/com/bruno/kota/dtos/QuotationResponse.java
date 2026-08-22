package com.bruno.kota.dtos;

import java.time.LocalDateTime;

import com.bruno.kota.entities.QuotationStatus;

public record QuotationResponse(
        Long id,
        String name,
        QuotationStatus status,
        Long supplierGroupId,
        String supplierGroupName,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        LocalDateTime expirationDate,
        LocalDateTime updatedAt,
        Integer defaultSalesProjectionDays,
        // true = pelo menos um fornecedor enviou lance antes do prazo. Usado no frontend
        // pra diferenciar uma cotação EXPIRED "morta" (ninguém respondeu, nada a fazer)
        // de uma EXPIRED com lances pendentes de fechamento (ainda dá pra calcular
        // vencedores) — a segunda não deveria assustar o admin com um badge vermelho de
        // "falhou" quando na verdade só falta ele clicar em Fechar.
        boolean hasBids
) {}