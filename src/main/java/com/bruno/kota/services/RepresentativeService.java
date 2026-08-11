package com.bruno.kota.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.RepresentativeRequest;
import com.bruno.kota.dtos.RepresentativeResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.RepresentativeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepresentativeService {

    private final RepresentativeRepository representativeRepository;

    @Transactional(readOnly = true)
    public List<RepresentativeResponse> findAll() {
        return representativeRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RepresentativeResponse findById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional
    public RepresentativeResponse create(RepresentativeRequest request) {
        Representative existing = representativeRepository.findByCpfIncludingDeleted(request.cpf()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um representante inativo com este CPF (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um representante com o CPF " + request.cpf());
        }

        Representative representative = Representative.builder()
                .cpf(request.cpf())
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .build();

        return toResponse(representativeRepository.save(representative));
    }

    @Transactional
    public RepresentativeResponse update(Long id, RepresentativeRequest request) {
        Representative representative = findEntityById(id);
        representative.setCpf(request.cpf());
        representative.setName(request.name());
        representative.setPhone(request.phone());
        representative.setEmail(request.email());
        return toResponse(representativeRepository.save(representative));
    }

    @Transactional
    public RepresentativeResponse reactivate(Long id, RepresentativeRequest request) {
        Representative representative = representativeRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + id));
        representative.setDeleted(false);
        representative.setName(request.name());
        representative.setPhone(request.phone());
        representative.setEmail(request.email());
        return toResponse(representativeRepository.save(representative));
    }

    @Transactional
    public void delete(Long id) {
        representativeRepository.delete(findEntityById(id));
    }

    private Representative findEntityById(Long id) {
        return representativeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + id));
    }

    @Transactional(readOnly = true)
    public List<RepresentativeResponse> findAllInactive() {
        return representativeRepository.findAllInactive().stream()
                .map(this::toResponse)
                .toList();
    }

    private RepresentativeResponse toResponse(Representative representative) {
        return new RepresentativeResponse(
                representative.getId(),
                representative.getCpf(),
                representative.getName(),
                representative.getPhone(),
                representative.getEmail()
        );
    }
}