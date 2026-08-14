package com.bruno.kota.dtos;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BidRequest(
        @NotNull(message = "Item da cotação é obrigatório")
        Long quotationItemId,

        @NotNull(message = "Fornecedor é obrigatório")
        Long supplierId,

        @NotNull(message = "Representante é obrigatório")
        Long submittedById,

        @NotNull(message = "Valor é obrigatório")
        @Positive(message = "Valor deve ser maior que zero")
        BigDecimal value,

        Integer deliveryDeadlineDays,

        String notes
) {}