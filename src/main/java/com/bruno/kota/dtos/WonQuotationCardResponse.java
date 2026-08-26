package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Versão "leve" de WonQuotationSummary, sem a lista de itens embutida — usada só pelo
// card fechado em "O que eu ganhei" (nome, data, contagem, total). Os itens de verdade
// só trafegam quando o card é expandido, um "page" por vez, via
// GET /quotations/{id}/won-items (ver QuotationService.getWonItemsPage). Diferente de
// "Resultados de Cotações" (pending-fulfillment), que continua usando
// WonQuotationSummary com todos os itens de uma vez — lá o representante precisa ver e
// decidir sobre cada item pra confirmar/cortar, não é uma lista de navegação.
public record WonQuotationCardResponse(
        Long quotationId,
        String quotationName,
        LocalDateTime closedAt,
        int itemCount,
        BigDecimal total
) {}
