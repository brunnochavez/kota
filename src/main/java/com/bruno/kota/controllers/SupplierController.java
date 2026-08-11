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
import com.bruno.kota.dtos.SupplierRequest;
import com.bruno.kota.dtos.SupplierResponse;
import com.bruno.kota.services.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    public List<SupplierResponse> findAll() {
        return supplierService.findAll();
    }

    @GetMapping("/{id}")
    public SupplierResponse findById(@PathVariable Long id) {
        return supplierService.findById(id);
    }

    @GetMapping("/inactive")
    public List<SupplierResponse> findAllInactive() {
        return supplierService.findAllInactive();
    }

    @GetMapping("/by-group/{groupId}")
    public List<SupplierResponse> findByGroup(@PathVariable Long groupId) {
        return supplierService.findByGroupId(groupId);
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.create(request));
    }

    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    public SupplierResponse reactivate(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.reactivate(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/groups/{groupId}")
    public SupplierResponse addToGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return supplierService.addToGroup(id, groupId);
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    public SupplierResponse removeFromGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return supplierService.removeFromGroup(id, groupId);
    }
}