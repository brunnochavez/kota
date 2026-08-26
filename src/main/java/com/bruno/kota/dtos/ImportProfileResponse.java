package com.bruno.kota.dtos;

public record ImportProfileResponse(
        Long id,
        Integer descriptionColumn,
        Integer barcodeColumn,
        Integer quantityColumn,
        Integer costColumn,
        String headerSignature
) {}
