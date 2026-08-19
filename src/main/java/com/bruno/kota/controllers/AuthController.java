package com.bruno.kota.controllers;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.ChangePasswordRequest;
import com.bruno.kota.dtos.LoginRequest;
import com.bruno.kota.dtos.LoginResponse;
import com.bruno.kota.security.AuthPrincipal;
import com.bruno.kota.services.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // Precisa de token válido pra chegar aqui (não está na lista de rotas públicas do
    // SecurityConfig) — é assim que sabe QUEM está trocando a própria senha, sem
    // precisar de nenhum parâmetro de id na URL.
    @PostMapping("/change-password")
    public void changePassword(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changeOwnPassword(principal.userId(), request);
    }
}
