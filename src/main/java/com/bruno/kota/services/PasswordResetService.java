package com.bruno.kota.services;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.PasswordResetToken;
import com.bruno.kota.entities.Representative;
import com.bruno.kota.entities.User;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.repositories.PasswordResetTokenRepository;
import com.bruno.kota.repositories.RepresentativeRepository;
import com.bruno.kota.repositories.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Núcleo dos dois fluxos de "defina sua senha via link" — convite de acesso novo
// (chamado por RepresentativeService.create()) e "esqueci minha senha"
// (PasswordResetController, público). O texto do e-mail muda entre os dois; o
// mecanismo do token (emissão, expiração, uso único) é o mesmo pros dois casos.
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RepresentativeRepository representativeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    private static final int TOKEN_EXPIRATION_HOURS = 48;

    // "Esqueci minha senha" — pedido público, só com o e-mail. Sempre silencioso sobre
    // se o e-mail existe ou não: se não existir conta com esse e-mail (ou ela estiver
    // desativada), simplesmente não faz nada, sem erro nenhum — o controller devolve a
    // mesma resposta genérica dos dois jeitos. Isso evita que alguém use esse endpoint
    // pra descobrir quais e-mails têm conta no sistema só testando um por um.
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            log.info("Pedido de redefinição de senha pra e-mail sem conta ativa correspondente — ignorado silenciosamente.");
            return;
        }
        String token = issueToken(user);
        emailService.sendPasswordSetupEmail(new EmailService.RepContact(displayName(user), user.getEmail()), token, false);
    }

    // Convite de acesso novo — chamado pelo backend logo depois de criar o User
    // (RepresentativeService.create() ou UserService, dependendo do fluxo). Diferente
    // de requestReset(), aqui já se sabe que o usuário existe (acabou de ser criado),
    // então não precisa da mesma cautela de silêncio.
    @Transactional
    public void sendAccessInvite(User user, String representativeName) {
        String token = issueToken(user);
        emailService.sendPasswordSetupEmail(new EmailService.RepContact(representativeName, user.getEmail()), token, true);
    }

    private String issueToken(User user) {
        tokenRepository.deleteByUserId(user.getId());
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        tokenRepository.save(PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRATION_HOURS))
                .used(false)
                .build());
        return token;
    }

    // Chamado pela tela pública set-password.html. Confere existência, uso único e
    // validade antes de aceitar a senha nova — qualquer uma dessas falhas vira uma
    // mensagem específica pro usuário entender o que fazer (pedir um link novo, etc.),
    // em vez de um "algo deu errado" genérico.
    @Transactional
    public void confirmReset(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new BusinessRuleException("Link inválido — confira se copiou o endereço completo, ou peça um novo."));

        if (Boolean.TRUE.equals(resetToken.getUsed())) {
            throw new BusinessRuleException("Esse link já foi usado. Se precisar trocar a senha de novo, peça um novo link.");
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("Esse link expirou. Peça um novo em \"Esqueci minha senha\".");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Diferente do fluxo de reset feito por um admin (RepresentativeAccessController/
        // AdminUserController), aqui foi o próprio dono que escolheu a senha — ninguém
        // mais chegou a vê-la, então não precisa forçar troca no próximo login.
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    private String displayName(User user) {
        return representativeRepository.findByUserId(user.getId())
                .map(Representative::getName)
                .orElse(user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getEmail());
    }
}
