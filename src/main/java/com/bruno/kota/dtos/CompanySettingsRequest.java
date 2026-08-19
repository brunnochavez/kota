package com.bruno.kota.dtos;

import jakarta.validation.constraints.NotBlank;

public record CompanySettingsRequest(
        @NotBlank(message = "Nome é obrigatório")
        String name,

        String cnpj,
        String stateRegistration,
        String email,
        String phone,
        String address,
        String neighborhood,
        String city,
        String state,
        String zipCode
) {}
