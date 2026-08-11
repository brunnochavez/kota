package com.bruno.kota.dtos;

import jakarta.validation.constraints.NotBlank;

public record ProductRequest(
        @NotBlank(message = "Código de barras é obrigatório")
        String barcode,

        @NotBlank(message = "Nome é obrigatório")
        String name,

        String description,
        String unitOfMeasure
) {}