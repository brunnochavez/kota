package com.bruno.kota.controllers;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.bruno.kota.security.AuthPrincipal;
import com.bruno.kota.services.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    // Antes devolvia a lista INTEIRA de fornecedores (todos os grupos, todo mundo) pra
    // qualquer usuário autenticado, inclusive representante — que assim via pedido
    // mínimo, contato etc de concorrentes que nunca deveria enxergar. Agora, se quem
    // pediu é representante, devolve só os fornecedores dele mesmo.
    @GetMapping
    public List<SupplierResponse> findAll(@AuthenticationPrincipal AuthPrincipal principal) {
        Long repId = (principal != null && !principal.isAdmin()) ? principal.representativeId() : null;
        return repId != null ? supplierService.findAllForRepresentative(repId) : supplierService.findAll();
    }

    // ADMIN só — a tela de representante nunca busca fornecedor por id nem por grupo
    // (só usa o GET /suppliers de cima, já filtrado pra ele). Abrir esses dois pra
    // qualquer autenticado deixava um representante ver detalhe de QUALQUER fornecedor
    // (inclusive concorrente) só sabendo o id.
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse findById(@PathVariable Long id) {
        return supplierService.findById(id);
    }

    @GetMapping("/inactive")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SupplierResponse> findAllInactive() {
        return supplierService.findAllInactive();
    }

    @GetMapping("/by-group/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SupplierResponse> findByGroup(@PathVariable Long groupId) {
        return supplierService.findByGroupId(groupId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse reactivate(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.reactivate(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Diferente do delete() acima (que só desativa) — esse exclui de verdade, e só
    // funciona quando o fornecedor nunca foi usado. Ver SupplierService.hardDelete.
    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> hardDelete(@PathVariable Long id) {
        supplierService.hardDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/groups/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse addToGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return supplierService.addToGroup(id, groupId);
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse removeFromGroup(@PathVariable Long id, @PathVariable Long groupId) {
        return supplierService.removeFromGroup(id, groupId);
    }
}