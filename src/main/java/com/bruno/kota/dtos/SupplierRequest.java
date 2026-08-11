package com.bruno.kota.dtos;

import java.math.BigDecimal;

import org.hibernate.validator.constraints.br.CNPJ;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SupplierRequest(
        @NotBlank(message = "Nome é obrigatório")
        String name,

        @NotBlank(message = "CNPJ é obrigatório")
        @CNPJ(message = "CNPJ inválido")
        String cnpj,

        @NotBlank(message = "Telefone é obrigatório")
        String phone,

        @NotBlank(message = "Endereço é obrigatório")
        String address,

        @NotNull(message = "Pedido mínimo é obrigatório")
        @Positive(message = "Pedido mínimo deve ser maior que zero")
        BigDecimal minimumOrderValue,

        Long representativeId
) {}