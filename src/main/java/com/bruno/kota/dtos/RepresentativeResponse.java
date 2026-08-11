package com.bruno.kota.dtos;

public record RepresentativeResponse(
        Long id,
        String cpf,
        String name,
        String phone,
        String email
) {}