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
        LocalDateTime expirationDate,
        Integer defaultSalesProjectionDays,

        @NotEmpty(message = "A cotação precisa de pelo menos um item")
        @Valid
        List<QuotationItemCreateRequest> items
) {}