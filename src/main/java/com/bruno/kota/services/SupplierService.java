package com.bruno.kota.services;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.dtos.SupplierRequest;
import com.bruno.kota.dtos.SupplierResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.OrderFulfillmentConfirmationRepository;
import com.bruno.kota.repositories.QuotationDeclineRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.SupplierGroupRepository;
import com.bruno.kota.repositories.SupplierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final RepresentativeRepository representativeRepository;
    private final SupplierGroupRepository supplierGroupRepository;
    private final BidRepository bidRepository;
    private final QuotationDeclineRepository quotationDeclineRepository;
    private final OrderFulfillmentConfirmationRepository orderFulfillmentConfirmationRepository;

    @Transactional(readOnly = true)
    public List<SupplierResponse> findAll() {
        return supplierRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> findAllInactive() {
        return supplierRepository.findAllInactive().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> findByGroupId(Long groupId) {
        SupplierGroup group = supplierGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + groupId));
        return supplierRepository.findByGroup(group).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        Supplier existing = supplierRepository.findByCnpjIncludingDeleted(request.cnpj()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um fornecedor inativo com este CNPJ (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um fornecedor com o CNPJ " + request.cnpj());
        }

        validateDeliveryDeadline(request.defaultDeliveryDeadlineDays());

        Supplier supplier = Supplier.builder()
                .name(request.name())
                .cnpj(request.cnpj())
                .phone(request.phone())
                .address(request.address())
                .minimumOrderValue(request.minimumOrderValue())
                .representative(resolveRepresentative(request.representativeId()))
                .defaultDeliveryDeadlineDays(request.defaultDeliveryDeadlineDays())
                .build();

        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse update(Long id, SupplierRequest request) {
        validateDeliveryDeadline(request.defaultDeliveryDeadlineDays());

        Supplier supplier = findEntityById(id);
        supplier.setName(request.name());
        supplier.setCnpj(request.cnpj());
        supplier.setPhone(request.phone());
        supplier.setAddress(request.address());
        supplier.setMinimumOrderValue(request.minimumOrderValue());
        supplier.setRepresentative(resolveRepresentative(request.representativeId()));
        supplier.setDefaultDeliveryDeadlineDays(request.defaultDeliveryDeadlineDays());
        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse reactivate(Long id, SupplierRequest request) {
        validateDeliveryDeadline(request.defaultDeliveryDeadlineDays());

        Supplier supplier = supplierRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + id));
        supplier.setDeleted(false);
        supplier.setName(request.name());
        supplier.setPhone(request.phone());
        supplier.setAddress(request.address());
        supplier.setMinimumOrderValue(request.minimumOrderValue());
        supplier.setRepresentative(resolveRepresentative(request.representativeId()));
        supplier.setDefaultDeliveryDeadlineDays(request.defaultDeliveryDeadlineDays());
        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public void delete(Long id) {
        supplierRepository.delete(findEntityById(id));
    }

    // Exclusão DE VERDADE (não é desativar) — só permitida quando o fornecedor nunca
    // participou de nada: nenhum lance enviado, nenhuma cotação recusada ("Não Cotar"),
    // nenhuma confirmação de pedido. Cobre tanto ativo quanto inativo (findByIdIncludingDeleted),
    // já que faz sentido limpar um fornecedor inativo que nunca chegou a ser usado.
    @Transactional
    public void hardDelete(Long id) {
        supplierRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + id));

        boolean used = bidRepository.existsBySupplierId(id)
                || quotationDeclineRepository.existsBySupplierId(id)
                || orderFulfillmentConfirmationRepository.existsBySupplierId(id);
        if (used) {
            throw new BusinessRuleException(
                    "Não é possível excluir — esse fornecedor já tem histórico de participação em cotações. Desative em vez de excluir.");
        }

        supplierRepository.hardDeleteById(id);
    }

    @Transactional
    public SupplierResponse addToGroup(Long supplierId, Long groupId) {
        Supplier supplier = findEntityById(supplierId);
        SupplierGroup group = supplierGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado: id " + groupId));
        supplier.getGroups().add(group);
        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse removeFromGroup(Long supplierId, Long groupId) {
        Supplier supplier = findEntityById(supplierId);
        supplier.getGroups().removeIf(group -> group.getId().equals(groupId));
        return toResponse(supplierRepository.save(supplier));
    }

    private Representative resolveRepresentative(Long representativeId) {
        if (representativeId == null) {
            return null;
        }
        return representativeRepository.findById(representativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + representativeId));
    }

    private void validateDeliveryDeadline(Integer defaultDeliveryDeadlineDays) {
        if (defaultDeliveryDeadlineDays != null && defaultDeliveryDeadlineDays <= 0) {
            throw new BusinessRuleException("Prazo de entrega deve ser maior que zero.");
        }
    }

    private Supplier findEntityById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: id " + id));
    }

    private SupplierResponse toResponse(Supplier supplier) {
        Representative rep = supplier.getRepresentative();
        List<Long> groupIds = supplier.getGroups().stream().map(SupplierGroup::getId).toList();
        List<String> groupNames = supplier.getGroups().stream().map(SupplierGroup::getName).toList();
        return new SupplierResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getCnpj(),
                supplier.getPhone(),
                supplier.getAddress(),
                supplier.getMinimumOrderValue(),
                rep != null ? rep.getId() : null,
                rep != null ? rep.getName() : null,
                groupIds,
                groupNames,
                supplier.getDefaultDeliveryDeadlineDays()
        );
    }
}