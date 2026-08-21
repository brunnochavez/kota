package com.bruno.kota.services;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.dtos.SupplierGroupRequest;
import com.bruno.kota.dtos.SupplierGroupResponse;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.SupplierGroupRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SupplierGroupService {

    private final SupplierGroupRepository supplierGroupRepository;

    @Transactional(readOnly = true)
    public List<SupplierGroupResponse> findAll() {
        return supplierGroupRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierGroupResponse> findAllInactive() {
        return supplierGroupRepository.findAllInactive().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierGroupResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional
    public SupplierGroupResponse create(SupplierGroupRequest request) {
        SupplierGroup existing = supplierGroupRepository.findByNameIncludingDeleted(request.name()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um grupo inativo com este nome (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um grupo com o nome " + request.name());
        }

        SupplierGroup group = SupplierGroup.builder()
                .name(request.name())
                .build();

        return toResponse(supplierGroupRepository.save(group));
    }

    @Transactional
    public SupplierGroupResponse update(Long id, SupplierGroupRequest request) {
        SupplierGroup group = findEntityById(id);
        group.setName(request.name());
        return toResponse(supplierGroupRepository.save(group));
    }

    @Transactional
    public SupplierGroupResponse reactivate(Long id) {
        SupplierGroup group = supplierGroupRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + id));
        group.setDeleted(false);
        return toResponse(supplierGroupRepository.save(group));
    }

    @Transactional
    public void delete(Long id) {
        supplierGroupRepository.delete(findEntityById(id));
    }

    private SupplierGroup findEntityById(Long id) {
        return supplierGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + id));
    }

    private SupplierGroupResponse toResponse(SupplierGroup group) {
        return new SupplierGroupResponse(group.getId(), group.getName());
    }
}