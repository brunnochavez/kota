package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.util.List;

public record SupplierResponse(
        Long id,
        String name,
        String cnpj,
        String phone,
        String address,
        BigDecimal minimumOrderValue,
        Long representativeId,
        String representativeName,
        List<Long> groupIds,
        List<String> groupNames,
        Integer defaultDeliveryDeadlineDays
) {}