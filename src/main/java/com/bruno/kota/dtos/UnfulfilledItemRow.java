package com.bruno.kota.dtos;

import java.math.BigDecimal;

// Uma linha da tela "Produtos sem atendimento" (visão geral, cruzando TODAS as cotações
// fechadas) — cut=true é "cortado por falta de estoque" (o representante venceu e depois
// confirmou que não tinha em estoque), cut=false é "sem nenhum lance" (ninguém ofertou).
// Mutuamente exclusivos por definição: um item só é "cortado" se teve vencedor primeiro,
// então nunca aparece nas duas categorias ao mesmo tempo.
public record UnfulfilledItemRow(
        Long quotationId,
        String quotationName,
        Long quotationItemId,
        Long productId,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        boolean cut
) {}
