package com.bruno.kota.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.RepresentativeRequest;
import com.bruno.kota.dtos.RepresentativeResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.User;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.InactiveResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.BidRepository;
import com.bruno.kota.repositories.QuotationDeclineRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.SupplierRepository;
import com.bruno.kota.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepresentativeService {

    private final RepresentativeRepository representativeRepository;
    private final BidRepository bidRepository;
    private final QuotationDeclineRepository quotationDeclineRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;

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
        Representative existing = representativeRepository.findByEmailIncludingDeleted(request.email()).orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new InactiveResourceException(
                        "Já existe um representante inativo com este e-mail (id " + existing.getId() + "). Reative-o em vez de criar um novo.",
                        existing.getId()
                );
            }
            throw new DuplicateResourceException("Já existe um representante com o e-mail " + request.email());
        }

        Representative representative = Representative.builder()
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .build();

        return toResponse(representativeRepository.save(representative));
    }

    @Transactional
    public RepresentativeResponse update(Long id, RepresentativeRequest request) {
        Representative representative = findEntityById(id);
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

    // Exclusão DE VERDADE — só permitida quando o representante nunca participou de
    // nada (nenhum lance enviado, nenhuma cotação recusada) e não está vinculado como
    // contato de nenhum fornecedor no momento. Também limpa o User (login) associado,
    // já que um acesso sem representante nenhum apontando pra ele é lixo — o User em si
    // não tem @SQLDelete, então esse delete aqui já é de verdade sem precisar de bypass.
    @Transactional
    public void hardDelete(Long id) {
        Representative representative = representativeRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + id));

        boolean used = bidRepository.existsBySubmittedById(id) || quotationDeclineRepository.existsByDeclinedById(id);
        if (used) {
            throw new BusinessRuleException(
                    "Não é possível excluir — esse representante já tem histórico de participação em cotações. Desative em vez de excluir.");
        }
        if (supplierRepository.existsByRepresentativeId(id)) {
            throw new BusinessRuleException(
                    "Esse representante ainda está vinculado como contato de um fornecedor. Desvincule antes de excluir.");
        }

        User user = representative.getUser();
        representativeRepository.hardDeleteById(id);
        if (user != null) {
            userRepository.delete(user);
        }
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
                representative.getName(),
                representative.getPhone(),
                representative.getEmail()
        );
    }
}