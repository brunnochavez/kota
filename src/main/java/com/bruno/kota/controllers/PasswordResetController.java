package com.bruno.kota.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.ConfirmPasswordResetRequest;
import com.bruno.kota.dtos.RequestPasswordResetRequest;
import com.bruno.kota.services.PasswordResetService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Público de propósito — quem está pedindo isso, por definição, ainda não consegue
// logar (esqueceu a senha, ou é um convite de acesso novo). Sem @PreAuthorize nenhum;
// a segurança do fluxo vem do token em si (aleatório, de uso único, expira em 48h),
// não de autenticação prévia.
@RestController
@RequestMapping("/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/request")
    public ResponseEntity<Void> requestReset(@Valid @RequestBody RequestPasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody ConfirmPasswordResetRequest request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
