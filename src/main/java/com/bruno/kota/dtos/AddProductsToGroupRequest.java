package com.bruno.kota.dtos;
import java.util.List;

public record AddProductsToGroupRequest(
        List<Long> productIds
) {}
