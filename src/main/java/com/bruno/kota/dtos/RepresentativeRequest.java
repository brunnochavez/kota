package com.bruno.kota.dtos;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RepresentativeRequest(
        @NotBlank(message = "Nome é obrigatório")
        String name,

        @NotBlank(message = "Telefone é obrigatório")
        String phone,

        @NotBlank(message = "E-mail é obrigatório")
        @Email(message = "E-mail inválido")
        String email
) {}