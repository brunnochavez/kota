package com.bruno.kota.dtos;

import java.math.BigDecimal;

public record QuotationItemResponse(
        Long id,
        Long quotationId,
        Long productId,
        String productName,
        String productBarcode,
        BigDecimal quantity,
        Long winningBidId,
        boolean fulfillmentCut,
        Integer salesProjectionDaysOverride,
        Integer effectiveSalesProjectionDays,
        // Só preenchido quando findItems(quotationId, supplierId) é chamado com um
        // supplierId — permite a tela de lançamento do representante pré-preencher o
        // preço já digitado sem precisar de uma chamada extra por item (era o que
        // causava a demora no primeiro carregamento de cotações grandes: N chamadas
        // pra N itens, uma por item, só pra saber se já tinha lance).
        Long myBidId,
        BigDecimal myBidValue
) {}