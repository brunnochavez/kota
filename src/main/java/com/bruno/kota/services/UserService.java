package com.bruno.kota.services;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.AdminUserResponse;
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

    // ---------- Usuários Administradores ----------
    // Diferente do acesso de representante (1:1 com um Representative já cadastrado),
    // aqui o usuário É a conta — não existe cadastro de "pessoa" separado por trás.
    // Só cria com role ADMIN: essa tela é especificamente pra dar acesso completo ao
    // painel a outra pessoa que vai ajudar a gerenciar cotações, não pra criar acesso de
    // representante (isso já existe em createAccess acima).

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listAdmins() {
        return userRepository.findByRole(UserRole.ADMIN).stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public AdminUserResponse createAdmin(CreateAccessRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateResourceException("Já existe um usuário cadastrado com esse e-mail.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.ADMIN)
                .enabled(true)
                .build();
        user = userRepository.save(user);
        return toAdminResponse(user);
    }

    @Transactional
    public AdminUserResponse resetAdminPassword(Long userId, String newPassword) {
        User user = findAdmin(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        return toAdminResponse(user);
    }

    // requestingUserId vem do token de quem está fazendo a chamada (AuthPrincipal) — as
    // duas checagens abaixo existem pra ninguém conseguir se trancar pra fora do próprio
    // sistema sem querer: não dá pra desativar a si mesmo, nem desativar o último admin
    // ainda ativo (mesmo que seja outro usuário), porque aí não sobraria ninguém pra
    // reverter a ação.
    @Transactional
    public AdminUserResponse setAdminEnabled(Long userId, boolean enabled, Long requestingUserId) {
        User user = findAdmin(userId);
        if (!enabled) {
            if (user.getId().equals(requestingUserId)) {
                throw new BusinessRuleException("Você não pode desativar seu próprio acesso.");
            }
            long enabledAdminCount = userRepository.findByRole(UserRole.ADMIN).stream()
                    .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
                    .count();
            if (enabledAdminCount <= 1) {
                throw new BusinessRuleException("Não é possível desativar o último administrador ativo.");
            }
        }
        user.setEnabled(enabled);
        userRepository.save(user);
        return toAdminResponse(user);
    }

    private User findAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: id " + userId));
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessRuleException("Esse usuário não é um administrador.");
        }
        return user;
    }

    private AdminUserResponse toAdminResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getEnabled(), user.getMustChangePassword());
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
