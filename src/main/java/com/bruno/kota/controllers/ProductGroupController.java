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

import com.bruno.kota.dtos.ProductGroupRequest;
import com.bruno.kota.dtos.ProductGroupResponse;
import com.bruno.kota.services.ProductGroupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/product-groups")
@RequiredArgsConstructor
public class ProductGroupController {

    private final ProductGroupService productGroupService;

    @GetMapping
    public List<ProductGroupResponse> findAll() {
        return productGroupService.findAll();
    }

    @GetMapping("/{id}")
    public ProductGroupResponse findById(@PathVariable Long id) {
        return productGroupService.findById(id);
    }

    @PostMapping
    public ResponseEntity<ProductGroupResponse> create(@Valid @RequestBody ProductGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productGroupService.create(request));
    }

    @PutMapping("/{id}")
    public ProductGroupResponse update(@PathVariable Long id, @Valid @RequestBody ProductGroupRequest request) {
        return productGroupService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    public ProductGroupResponse reactivate(@PathVariable Long id) {
        return productGroupService.reactivate(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productGroupService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
