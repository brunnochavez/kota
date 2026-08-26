package com.bruno.kota.dtos;

import java.time.LocalDateTime;
import java.util.List;

import com.bruno.kota.entities.QuotationStatus;

public record QuotationResponse(
        Long id,
        String name,
        QuotationStatus status,
        Long supplierGroupId,
        String supplierGroupName,

        // Fornecedores avulsos (extras ao grupo) — paralelos entre si por índice
        // (extraSupplierIds[i] é o id de extraSupplierNames[i]), mesmo padrão simples já
        // usado no resto do projeto pra não precisar de mais um DTO só pra isso.
        List<Long> extraSupplierIds,
        List<String> extraSupplierNames,

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
