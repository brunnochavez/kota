package com.bruno.kota.dtos;

import jakarta.validation.constraints.NotBlank;

public record ProductGroupRequest(
        @NotBlank(message = "Nome é obrigatório")
        String name
) {}
