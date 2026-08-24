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

import com.bruno.kota.dtos.SupplierGroupRequest;
import com.bruno.kota.dtos.SupplierGroupResponse;
import com.bruno.kota.services.SupplierGroupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/supplier-groups")
@RequiredArgsConstructor
public class SupplierGroupController {

    private final SupplierGroupService supplierGroupService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<SupplierGroupResponse> findAll() {
        return supplierGroupService.findAll();
    }

    @GetMapping("/inactive")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SupplierGroupResponse> findAllInactive() {
        return supplierGroupService.findAllInactive();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierGroupResponse findById(@PathVariable Long id) {
        return supplierGroupService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SupplierGroupResponse> create(@Valid @RequestBody SupplierGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierGroupService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierGroupResponse update(@PathVariable Long id, @Valid @RequestBody SupplierGroupRequest request) {
        return supplierGroupService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierGroupResponse reactivate(@PathVariable Long id) {
        return supplierGroupService.reactivate(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierGroupService.delete(id);
        return ResponseEntity.noContent().build();
    }
}