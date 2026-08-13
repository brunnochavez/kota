package com.bruno.kota.dtos;
import java.math.BigDecimal;

// secondBestValue/secondBestSupplierName vêm null quando não existe outro lance elegível
// pra esse item além do vencedor atual — não tem "segunda opção" pra sugerir.
public record MinimumOrderViolationItem(
        Long quotationItemId,
        String productName,
        BigDecimal quantity,
        BigDecimal winningValue,
        BigDecimal secondBestValue,
        String secondBestSupplierName
) {}
