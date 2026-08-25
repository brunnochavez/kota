package com.bruno.kota.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// Só o e-mail de login — não pede mais senha aqui. O acesso é criado com uma senha
// aleatória que ninguém (nem o admin) chega a saber, e o representante define a
// própria via o link de convite que recebe por e-mail (ver
// UserService.createAccess/PasswordResetService.sendAccessInvite).
public record CreateAccessRequest(
        @NotBlank(message = "E-mail é obrigatório")
        @Email(message = "E-mail inválido")
        String email
) {}
