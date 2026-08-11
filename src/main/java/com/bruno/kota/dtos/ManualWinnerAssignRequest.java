package com.bruno.kota.dtos;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ManualWinnerAssignRequest(
        @NotNull(message = "Fornecedor é obrigatório")
        Long supplierId,

        @NotNull(message = "Representante é obrigatório")
        Long representativeId,

        @NotNull(message = "Valor é obrigatório")
        @Positive(message = "Valor deve ser maior que zero")
        BigDecimal value,

        @NotNull(message = "Prazo de entrega é obrigatório")
        @Positive(message = "Prazo de entrega deve ser maior que zero")
        Integer deliveryDeadlineDays
) {}