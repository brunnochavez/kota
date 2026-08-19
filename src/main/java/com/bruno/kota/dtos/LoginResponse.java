package com.bruno.kota.dtos;

public record LoginResponse(
        String token,
        String role,
        String name,
        Long representativeId,
        boolean mustChangePassword
) {}
