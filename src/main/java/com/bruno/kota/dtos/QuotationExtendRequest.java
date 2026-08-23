package com.bruno.kota.dtos;

import java.time.LocalDateTime;

public record QuotationExtendRequest(
        @jakarta.validation.constraints.NotNull(message = "Novo prazo é obrigatório")
        LocalDateTime expirationDate
) {}
