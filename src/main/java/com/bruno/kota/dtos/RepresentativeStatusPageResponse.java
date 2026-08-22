package com.bruno.kota.dtos;

import java.util.List;

// Diferente do PagedResponse genérico: aqui, totalElements/totalPages refletem a busca
// atual (pra paginar a tabela), mas totalGroupSize/respondedCount são sempre do grupo
// INTEIRO, sem filtro nenhum — é o que alimenta o resumo fixo no topo do modal ("X de Y
// já responderam"), que não pode mudar só porque o admin digitou algo na busca.
public record RepresentativeStatusPageResponse(
        List<RepresentativeResponseStatus> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        int totalGroupSize,
        int respondedCount
) {}
