package com.bruno.kota.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @GetMapping
    public List<RepresentativeResponse> findAll() {
        return representativeService.findAll();
    }

    @GetMapping("/{id}")
    public RepresentativeResponse findById(@PathVariable Long id) {
        return representativeService.findById(id);
    }

    @PostMapping
    public ResponseEntity<RepresentativeResponse> create(@Valid @RequestBody RepresentativeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(representativeService.create(request));
    }

    @PutMapping("/{id}")
    public RepresentativeResponse update(@PathVariable Long id, @Valid @RequestBody RepresentativeRequest request) {
        return representativeService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    public RepresentativeResponse reactivate(@PathVariable Long id, @Valid @RequestBody RepresentativeRequest request) {
        return representativeService.reactivate(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        representativeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/inactive")
    public List<RepresentativeResponse> findAllInactive() {
        return representativeService.findAllInactive();
    }
}