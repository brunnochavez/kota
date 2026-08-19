package com.bruno.kota.controllers;
import java.util.List;
import com.bruno.kota.dtos.PagedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.bruno.kota.dtos.ProductRequest;
import com.bruno.kota.dtos.ProductResponse;
import com.bruno.kota.services.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public List<ProductResponse> findAll() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse reactivate(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.reactivate(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public PagedResponse<ProductResponse> search(
            @RequestParam(required = false) String term,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productService.search(term, page, size);
    }

    @GetMapping("/inactive")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProductResponse> findAllInactive() {
        return productService.findAllInactive();
    }

    @GetMapping("/by-group/{groupId}")
    public List<ProductResponse> findByGroup(@PathVariable Long groupId) {
        return productService.findByGroupId(groupId);
    }

    @PostMapping("/{id}/groups/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse addToGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return productService.addToGroup(id, groupId);
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse removeFromGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return productService.removeFromGroup(id, groupId);
    }
}