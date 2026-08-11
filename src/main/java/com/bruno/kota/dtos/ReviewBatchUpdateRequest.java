package com.bruno.kota.dtos;

import java.math.BigDecimal;
import java.util.List;

public record ReviewBatchUpdateRequest(
        List<BidPriceUpdateItem> bidUpdates,
        List<ItemQuantityUpdateItem> itemUpdates
) {
    public record BidPriceUpdateItem(Long bidId, BigDecimal value, Integer deliveryDeadlineDays) {}
    public record ItemQuantityUpdateItem(Long itemId, BigDecimal quantity) {}
}