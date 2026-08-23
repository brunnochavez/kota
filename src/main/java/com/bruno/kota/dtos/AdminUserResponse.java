package com.bruno.kota.dtos;

public record AdminUserResponse(
        Long id,
        String name,
        String email,
        boolean enabled,
        boolean mustChangePassword
) {}
