package com.bruno.kota.dtos;

public record RepresentativeAccessResponse(
        Long representativeId,
        boolean hasAccess,
        String email,
        Boolean enabled
) {}
