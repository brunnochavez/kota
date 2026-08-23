package com.bruno.kota.dtos;

public record RepresentativeResponse(
        Long id,
        String name,
        String phone,
        String email
) {}