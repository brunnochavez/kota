package com.bruno.kota.dtos;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record QuotationCreateRequest(
        @NotBlank(message = "Nome da cotação é obrigatório")
        String name,

        Long supplierGroupId,

        // Fornecedores avulsos, além (ou no lugar) do grupo — opcional, null/vazio
        // equivale a "nenhum". A cotação continua exigindo pelo menos UM fornecedor
        // elegível (grupo OU avulso) na hora de publicar, ver QuotationService.publish().
        List<Long> extraSupplierIds,

        LocalDateTime expirationDate,
        Integer defaultSalesProjectionDays,

        @NotEmpty(message = "A cotação precisa de pelo menos um item")
        @Valid
        List<QuotationItemCreateRequest> items
) {}
