package com.bruno.kota.services;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.dtos.ProductGroupRequest;
import com.bruno.kota.dtos.ProductGroupResponse;
import com.bruno.kota.entities.ProductGroup;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ProductGroupRepository;
import com.bruno.kota.repositories.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductGroupService {

    private final ProductGroupRepository productGroupRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<ProductGroupResponse> findAll() {
        List<ProductGroup> groups = productGroupRepository.findAll();
        Map<Long, Integer> countByGroupId = loadProductCounts(groups);
        return groups.stream()
                .map(g -> new ProductGroupResponse(g.getId(), g.getName(), countByGroupId.getOrDefault(g.getId(), 0)))
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

    // Usada por findAll() (lista completa) — 1 query de COUNT só, pra todos os grupos de
    // uma vez, em vez de 1 findByGroup(group).size() por grupo (que ainda por cima trazia
    // as linhas de Product inteiras só pra contar).
    private Map<Long, Integer> loadProductCounts(List<ProductGroup> groups) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<Long> groupIds = groups.stream().map(ProductGroup::getId).toList();
        Map<Long, Integer> countByGroupId = new HashMap<>();
        for (Object[] row : productRepository.countByGroupIds(groupIds)) {
            countByGroupId.put((Long) row[0], ((Long) row[1]).intValue());
        }
        return countByGroupId;
    }

    // Usado nos pontos que lidam com UM grupo só (findById, create, update, reactivate) —
    // aqui 1 query extra é aceitável, não é uma listagem em loop.
    private ProductGroupResponse toResponse(ProductGroup group) {
        int productCount = productRepository.findByGroup(group).size();
        return new ProductGroupResponse(group.getId(), group.getName(), productCount);
    }
}
