package com.bruno.kota.controllers;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.CreateAccessRequest;
import com.bruno.kota.dtos.LoginResponse;
import com.bruno.kota.dtos.RepresentativeAccessResponse;
import com.bruno.kota.security.AuthPrincipal;
import com.bruno.kota.services.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Tudo aqui é coisa de administrador gerenciando o acesso de outra pessoa — nenhum
// representante mexe na própria senha por essa rota (não existe fluxo de "esqueci minha
// senha" ainda; é o admin que reseta manualmente se precisar).
@RestController
@RequestMapping("/representatives/{representativeId}/access")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RepresentativeAccessController {

    private final UserService userService;

    @GetMapping
    public RepresentativeAccessResponse getStatus(@PathVariable Long representativeId) {
        return userService.getAccessStatus(representativeId);
    }

    @PostMapping
    public RepresentativeAccessResponse create(@PathVariable Long representativeId, @Valid @RequestBody CreateAccessRequest request) {
        return userService.createAccess(representativeId, request);
    }

    @PutMapping("/password")
    public RepresentativeAccessResponse resetPassword(@PathVariable Long representativeId, @RequestParam String newPassword) {
        return userService.resetPassword(representativeId, newPassword);
    }

    @PutMapping("/enabled")
    public RepresentativeAccessResponse setEnabled(@PathVariable Long representativeId, @RequestParam boolean enabled) {
        return userService.setEnabled(representativeId, enabled);
    }

    // "Ver como esse representante" — devolve um token de sessão REPRESENTATIVE pra o
    // admin abrir representante.html enxergando exatamente o que aquele representante
    // vê, sem precisar saber (ou redefinir) a senha dele.
    @PostMapping("/impersonate")
    public LoginResponse impersonate(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long representativeId) {
        return userService.impersonateRepresentative(representativeId, principal.displayName());
    }
}
