package com.bruno.kota.dtos;

import java.time.LocalDateTime;
import java.util.List;

public record QuotationUpdateRequest(
        @jakarta.validation.constraints.NotBlank(message = "Nome da cotação é obrigatório")
        String name,

        Long supplierGroupId,

        // null = "não mexe" seria ambíguo com "esvaziar a lista" (o front sempre manda a
        // lista completa e atual de fornecedores avulsos, igual já faz com o grupo) —
        // por isso QuotationService.update() sempre SUBSTITUI o conjunto inteiro por
        // este valor, tratando null como lista vazia.
        List<Long> extraSupplierIds,

        LocalDateTime expirationDate,
        Integer defaultSalesProjectionDays
) {}
