package com.bruno.kota.dtos;

// salesProjectionDays == null é um valor válido e proposital: significa "remover a
// sobrescrita e voltar a herdar o padrão da cotação" — por isso não tem @NotNull aqui.
// A validação de "maior que zero, quando informado" fica no service, não em Bean
// Validation, justamente pra poder aceitar null como caso legítimo.
public record SalesProjectionUpdateRequest(
        Integer salesProjectionDays
) {}
