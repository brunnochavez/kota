package com.bruno.kota.services;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.ChangePasswordRequest;
import com.bruno.kota.dtos.LoginRequest;
import com.bruno.kota.dtos.LoginResponse;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.User;
import com.bruno.kota.entities.UserRole;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.UserRepository;
import com.bruno.kota.security.JwtService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    // 5 tentativas erradas seguidas bloqueia a conta por 15 minutos. Número pequeno o
    // bastante pra travar um script de força bruta rápido, grande o bastante pra não
    // irritar alguém que só errou a senha duas vezes de verdade.
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MINUTES = 15;

    private final UserRepository userRepository;
    private final RepresentativeRepository representativeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // Mensagem de erro é a MESMA pra "email não existe" e "senha errada" — de propósito,
    // pra não dar pista de quais emails têm conta cadastrada no sistema.
    //
    // Mecanismo de força bruta, em 3 passos:
    //   1. Se a conta já está bloqueada (lockedUntil no futuro), recusa ANTES de conferir
    //      a senha — nem gasta o custo de BCrypt, e a mensagem já avisa quanto tempo falta.
    //   2. Senha errada → incrementa o contador; ao bater o limite, bloqueia por 15min.
    //   3. Senha certa → zera o contador e o bloqueio, login segue normal.
    // Complementado pelo LoginRateLimitFilter, que limita tentativas por IP ANTES de
    // chegar aqui — esse aqui é por CONTA, aquele é por ORIGEM da tentativa.
    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new BusinessRuleException("E-mail ou senha inválidos."));

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessRuleException("Esse acesso está desativado. Fale com o administrador.");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            long minutesLeft = java.time.temporal.ChronoUnit.MINUTES.between(LocalDateTime.now(), user.getLockedUntil()) + 1;
            throw new BusinessRuleException(
                    "Conta bloqueada por excesso de tentativas. Tente novamente em " + minutesLeft + " minuto(s).");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new BusinessRuleException("E-mail ou senha inválidos.");
        }

        if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        // Nome de exibição no login: representante sempre usa o nome cadastrado em
        // Representative (nunca teve outra opção). Admin agora também — usa o nome
        // definido em "Usuários Administradores" (User.name) quando existir, e só cai
        // pro e-mail como fallback pra contas antigas que ainda não tiveram nome
        // definido (ex: o admin@kota.com criado pelo AdminBootstrap).
        String name = (user.getName() != null && !user.getName().isBlank()) ? user.getName() : user.getEmail();
        Long representativeId = null;

        if (user.getRole() == UserRole.REPRESENTATIVE) {
            Representative representative = representativeRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new BusinessRuleException("Esse acesso não está vinculado a nenhum representante. Fale com o administrador."));
            representativeId = representative.getId();
            name = representative.getName();
        }

        String token = jwtService.generateToken(user, representativeId);
        return new LoginResponse(token, user.getRole().name(), name, representativeId, Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
        }
        userRepository.save(user);
    }

    // Sempre confere a senha ATUAL, mesmo na troca obrigatória logo após o login — a
    // pessoa acabou de digitar ela pra entrar, então não é uma exigência extra de
    // verdade, e mantém essa validação igual pra qualquer troca de senha futura
    // (obrigatória ou voluntária), sem precisar de dois caminhos diferentes no código.
    @Transactional
    public void changeOwnPassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessRuleException("Usuário não encontrado."));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("Senha atual incorreta.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }
}
