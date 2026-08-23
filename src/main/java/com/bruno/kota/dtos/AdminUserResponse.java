package com.bruno.kota.dtos;

public record AdminUserResponse(
        Long id,
        String email,
        boolean enabled,
        boolean mustChangePassword
) {}
