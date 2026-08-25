package com.bruno.kota.services;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.AdminUserResponse;
import com.bruno.kota.dtos.BulkInviteResult;
import com.bruno.kota.dtos.CreateAccessRequest;
import com.bruno.kota.dtos.CreateAdminUserRequest;
import com.bruno.kota.dtos.LoginResponse;
import com.bruno.kota.dtos.RepresentativeAccessResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.User;
import com.bruno.kota.entities.UserRole;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.exceptions.DuplicateResourceException;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.UserRepository;
import com.bruno.kota.security.JwtService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Cadastro de Representative (dado de negócio: nome, telefone, e-mail) e criação de acesso
// de login (email + senha) são intencionalmente duas ações separadas — o admin cadastra
// o representante primeiro, e só cria o acesso quando fizer sentido (pode nunca precisar,
// se o representante nunca for logar direto no sistema). Na prática, hoje o acesso já é
// criado automaticamente no cadastro (RepresentativeService.create()) — esse fluxo aqui
// (createAccess) só é usado como reforço/reenvio, pra representante cadastrado antes
// dessa automação existir, ou algum caso de borda em que o automático foi pulado.
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RepresentativeRepository representativeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordResetService passwordResetService;

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

        User user = provisionAccessAndInvite(representative, request.email());
        return toResponse(representativeId, user);
    }

    // "Convidar todos sem acesso" — pensado pra representante cadastrado ANTES do
    // convite automático existir (RepresentativeService.create() só passou a criar
    // acesso sozinho a partir de um certo ponto; quem já estava cadastrado antes disso
    // nunca teve acesso criado, e não teria como saber). Usa o e-mail que já está
    // cadastrado no próprio representante — sem campo de e-mail separado igual no
    // convite individual, já que aqui é em lote e não dá pra revisar um por um.
    @Transactional
    public BulkInviteResult inviteAllMissingAccess() {
        List<Representative> missing = representativeRepository.findAll().stream()
                .filter(r -> r.getUser() == null)
                .toList();

        int invited = 0;
        List<String> failedNames = new java.util.ArrayList<>();
        for (Representative representative : missing) {
            try {
                provisionAccessAndInvite(representative, representative.getEmail());
                invited++;
            } catch (Exception e) {
                log.error("Falha ao convidar representante {} no envio em lote: {}", representative.getId(), e.getMessage());
                failedNames.add(representative.getName());
            }
        }
        return new BulkInviteResult(invited, failedNames);
    }

    // Núcleo compartilhado entre o convite individual (createAccess) e o em lote
    // (inviteAllMissingAccess) — cria o User com senha aleatória (ninguém chega a
    // saber) e dispara o e-mail de convite. Falha no e-mail em si não derruba a criação
    // do acesso (fica só registrado no log) — o representante já pode pedir "esqueci
    // minha senha" depois, mesmo que esse envio específico não tenha saído.
    private User provisionAccessAndInvite(Representative representative, String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateResourceException("Já existe um usuário cadastrado com esse e-mail.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                .role(UserRole.REPRESENTATIVE)
                .enabled(true)
                .build();
        user = userRepository.save(user);

        representative.setUser(user);
        representativeRepository.save(representative);

        try {
            passwordResetService.sendAccessInvite(user, representative.getName());
        } catch (Exception e) {
            log.error("Falha ao enviar convite de acesso pro representante {}: {}", representative.getId(), e.getMessage());
        }

        return user;
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

    // "Ver como esse representante" — gera um token de sessão pro representante SEM o
    // admin precisar saber a senha dele (nem redefini-la, o que trocaria a senha de
    // verdade e avisaria/confundiria o representante). O token carrega a marca de quem
    // gerou (impersonatedBy, ver JwtService) só pra rastro — a sessão em si é uma sessão
    // REPRESENTATIVE de verdade, com tudo que ele veria. mustChangePassword sempre false
    // aqui: forçar troca de senha durante uma visualização do admin não faz sentido, e
    // trocaria a senha real do representante sem ele saber.
    @Transactional(readOnly = true)
    public LoginResponse impersonateRepresentative(Long representativeId, String impersonatedByLabel) {
        Representative representative = findRepresentative(representativeId);
        User user = representative.getUser();
        if (user == null) {
            throw new BusinessRuleException("Esse representante ainda não tem acesso criado — crie o acesso antes de visualizar como ele.");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessRuleException("O acesso desse representante está desativado.");
        }

        String token = jwtService.generateToken(user, representativeId, impersonatedByLabel);
        return new LoginResponse(token, user.getRole().name(), representative.getName(), representativeId, false);
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
    public AdminUserResponse createAdmin(CreateAdminUserRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateResourceException("Já existe um usuário cadastrado com esse e-mail.");
        }

        User user = User.builder()
                .name(request.name().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.ADMIN)
                .enabled(true)
                .build();
        user = userRepository.save(user);
        return toAdminResponse(user);
    }

    // Só pra dar nome a contas ADMIN que já existiam antes desse campo (o
    // admin@kota.com do AdminBootstrap, ou qualquer uma criada antes de "Nome" existir
    // no formulário) — sem isso, elas ficariam mostrando e-mail pra sempre no histórico.
    @Transactional
    public AdminUserResponse updateAdminName(Long userId, String name) {
        User user = findAdmin(userId);
        user.setName(name != null ? name.trim() : null);
        userRepository.save(user);
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
        return new AdminUserResponse(user.getId(), user.getName(), user.getEmail(), user.getEnabled(), user.getMustChangePassword());
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
