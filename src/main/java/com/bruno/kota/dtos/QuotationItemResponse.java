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
        BigDecimal myBidValue,
        // Opcional — só vem preenchido quando a cotação foi importada com "incluir
        // preços de custo" marcado E a planilha tinha algo naquela célula (ver
        // QuotationImportService). Usado em "Revisar Lances Enviados" pra indicar se o
        // lance vencedor representa aumento ou baixa frente ao que era pago antes.
        BigDecimal costPrice
) {}
