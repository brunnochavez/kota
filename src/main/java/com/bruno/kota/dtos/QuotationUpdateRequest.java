package com.bruno.kota.dtos;

import java.time.LocalDateTime;

public record QuotationUpdateRequest(
        @jakarta.validation.constraints.NotBlank(message = "Nome da cotação é obrigatório")
        String name,

        Long supplierGroupId,
        LocalDateTime expirationDate
) {}