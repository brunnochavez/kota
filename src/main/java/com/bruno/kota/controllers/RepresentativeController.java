package com.bruno.kota.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.RepresentativeRequest;
import com.bruno.kota.dtos.RepresentativeResponse;
import com.bruno.kota.services.RepresentativeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/representatives")
@RequiredArgsConstructor
public class RepresentativeController {

    private final RepresentativeService representativeService;

    // ADMIN só — devolve nome/telefone/e-mail de TODOS os representantes (concorrentes
    // entre si). A tela de representante nunca chama isso (ela já sabe quem é o próprio
    // usuário pelo token) — antes, qualquer autenticado conseguia listar todo mundo.
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<RepresentativeResponse> findAll() {
        return representativeService.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RepresentativeResponse findById(@PathVariable Long id) {
        return representativeService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RepresentativeResponse> create(@Valid @RequestBody RepresentativeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(representativeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RepresentativeResponse update(@PathVariable Long id, @Valid @RequestBody RepresentativeRequest request) {
        return representativeService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public RepresentativeResponse reactivate(@PathVariable Long id, @Valid @RequestBody RepresentativeRequest request) {
        return representativeService.reactivate(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        representativeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Diferente do delete() acima (que só desativa) — esse exclui de verdade, e só
    // funciona quando o representante nunca foi usado. Ver RepresentativeService.hardDelete.
    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> hardDelete(@PathVariable Long id) {
        representativeService.hardDelete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/inactive")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RepresentativeResponse> findAllInactive() {
        return representativeService.findAllInactive();
    }
}