package com.bruno.kota.controllers;

import java.util.List;

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

import com.bruno.kota.dtos.AdminUserResponse;
import com.bruno.kota.dtos.CreateAccessRequest;
import com.bruno.kota.security.AuthPrincipal;
import com.bruno.kota.services.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Gerenciamento de OUTROS usuários com acesso completo ao painel (role ADMIN) — pra
// dividir a gestão de cotações com mais gente, sem todo mundo precisar compartilhar a
// mesma conta admin@kota.com criada pelo AdminBootstrap. Não confundir com
// RepresentativeAccessController: aquele cria login (role REPRESENTATIVE) pra um
// Representative já cadastrado; este cria uma conta ADMIN nova, sem cadastro de pessoa
// por trás.
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public List<AdminUserResponse> list() {
        return userService.listAdmins();
    }

    @PostMapping
    public AdminUserResponse create(@Valid @RequestBody CreateAccessRequest request) {
        return userService.createAdmin(request);
    }

    @PutMapping("/{id}/password")
    public AdminUserResponse resetPassword(@PathVariable Long id, @RequestParam String newPassword) {
        return userService.resetAdminPassword(id, newPassword);
    }

    @PutMapping("/{id}/enabled")
    public AdminUserResponse setEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return userService.setAdminEnabled(id, enabled, principal.userId());
    }
}
