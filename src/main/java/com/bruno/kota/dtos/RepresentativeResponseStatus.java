package com.bruno.kota.dtos;

// status: "SUBMITTED" (enviou pelo menos um lance), "DECLINED" (clicou "Não Cotar") ou
// "PENDING" (ainda não respondeu de nenhuma forma). String simples em vez de enum
// porque é só pra exibição na tela — não entra em nenhuma regra de negócio.
public record RepresentativeResponseStatus(
        Long representativeId,
        String representativeName,
        String supplierName,
        String status
) {}
