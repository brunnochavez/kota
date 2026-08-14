package com.bruno.kota.services;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.dtos.ProductGroupRequest;
import com.bruno.kota.dtos.ProductGroupResponse;
import com.bruno.kota.entities.ProductGroup;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ProductGroupRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductGroupService {

    private final ProductGroupRepository productGroupRepository;

    @Transactional(readOnly = true)
    public List<ProductGroupResponse> findAll() {
        return productGroupRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductGroupResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional
    public ProductGroupResponse create(ProductGroupRequest request) {
        ProductGroup existing = productGroupRepository.findByNameIncludingDeleted(request.name()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um grupo inativo com este nome (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um grupo com o nome " + request.name());
        }

        ProductGroup group = ProductGroup.builder()
                .name(request.name())
                .build();

        return toResponse(productGroupRepository.save(group));
    }

    @Transactional
    public ProductGroupResponse update(Long id, ProductGroupRequest request) {
        ProductGroup group = findEntityById(id);
        group.setName(request.name());
        return toResponse(productGroupRepository.save(group));
    }

    @Transactional
    public ProductGroupResponse reactivate(Long id) {
        ProductGroup group = productGroupRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + id));
        group.setDeleted(false);
        return toResponse(productGroupRepository.save(group));
    }

    @Transactional
    public void delete(Long id) {
        productGroupRepository.delete(findEntityById(id));
    }

    private ProductGroup findEntityById(Long id) {
        return productGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + id));
    }

    private ProductGroupResponse toResponse(ProductGroup group) {
        return new ProductGroupResponse(group.getId(), group.getName());
    }
}
