package com.bruno.kota.dtos;

public record CompanySettingsResponse(
        Long id,
        String name,
        String cnpj,
        String stateRegistration,
        String email,
        String phone,
        String address,
        String neighborhood,
        String city,
        String state,
        String zipCode,
        String logoUrl
) {}
