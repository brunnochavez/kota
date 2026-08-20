package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Uma linha = um item vencido de uma cotação FECHADA que ainda faz sentido repor
// (tem vencedor, não foi cortado por falta de estoque, e tem os dois números
// necessários pro cálculo: prazo de entrega do fornecedor vencedor + projeção de
// venda efetiva do item). reorderDate é a data recomendada pra abrir o próximo
// pedido — calculada de trás pra frente a partir de quando o estoque deve esgotar,
// descontando o prazo de entrega do próximo pedido (mesma folga do atual).
public record ReorderPointRow(
        Long quotationId,
        String quotationName,
        LocalDateTime closedAt,
        Long quotationItemId,
        Long productId,
        String productName,
        String productBarcode,
        String supplierName,
        String representativeName,
        BigDecimal quantity,
        Integer deliveryDeadlineDays,
        Integer salesProjectionDays,
        LocalDateTime estimatedArrivalDate,
        LocalDateTime estimatedDepletionDate,
        LocalDateTime reorderDate,
        long daysUntilReorder
) {}
