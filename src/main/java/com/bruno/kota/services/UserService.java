package com.bruno.kota.services;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.CreateAccessRequest;
import com.bruno.kota.dtos.RepresentativeAccessResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.User;
import com.bruno.kota.entities.UserRole;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

// Cadastro de Representative (dado de negócio: nome, cpf, telefone) e criação de acesso
// de login (email + senha) são intencionalmente duas ações separadas — o admin cadastra
// o representante primeiro, e só cria o acesso quando fizer sentido (pode nunca precisar,
// se o representante nunca for logar direto no sistema).
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RepresentativeRepository representativeRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public RepresentativeAccessResponse getAccessStatus(Long representativeId) {
        Representative representative = findRepresentative(representativeId);
        User user = representative.getUser();
        return toResponse(representativeId, user);
    }

    @Transactional
    public RepresentativeAccessResponse createAccess(Long representativeId, CreateAccessRequest request) {
        Representative representative = findRepresentative(representativeId);

        if (representative.getUser() != null) {
            throw new BusinessRuleException("Esse representante já tem acesso criado — use redefinir senha em vez de criar de novo.");
        }

        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateResourceException("Já existe um usuário cadastrado com esse e-mail.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.REPRESENTATIVE)
                .enabled(true)
                .build();
        user = userRepository.save(user);

        representative.setUser(user);
        representativeRepository.save(representative);

        return toResponse(representativeId, user);
    }

    @Transactional
    public RepresentativeAccessResponse resetPassword(Long representativeId, String newPassword) {
        Representative representative = findRepresentative(representativeId);
        User user = representative.getUser();
        if (user == null) {
            throw new BusinessRuleException("Esse representante ainda não tem acesso criado.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        // Reset de senha pelo admin também limpa o bloqueio por força bruta — senão o
        // representante fica travado com a senha NOVA até o bloqueio antigo expirar
        // sozinho, o que não faz sentido nenhum (o motivo do bloqueio já não existe mais).
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        return toResponse(representativeId, user);
    }

    @Transactional
    public RepresentativeAccessResponse setEnabled(Long representativeId, boolean enabled) {
        Representative representative = findRepresentative(representativeId);
        User user = representative.getUser();
        if (user == null) {
            throw new BusinessRuleException("Esse representante ainda não tem acesso criado.");
        }
        user.setEnabled(enabled);
        userRepository.save(user);
        return toResponse(representativeId, user);
    }

    private Representative findRepresentative(Long id) {
        return representativeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Representante não encontrado: id " + id));
    }

    private RepresentativeAccessResponse toResponse(Long representativeId, User user) {
        if (user == null) {
            return new RepresentativeAccessResponse(representativeId, false, null, null);
        }
        return new RepresentativeAccessResponse(representativeId, true, user.getEmail(), user.getEnabled());
    }
}
