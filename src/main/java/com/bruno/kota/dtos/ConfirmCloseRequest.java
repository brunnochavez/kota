package com.bruno.kota.dtos;

import java.util.List;

public record ConfirmCloseRequest(
        List<Long> acceptedViolationSupplierIds
) {}
