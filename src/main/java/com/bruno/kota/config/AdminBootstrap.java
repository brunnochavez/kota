package com.bruno.kota.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.bruno.kota.entities.User;
import com.bruno.kota.entities.UserRole;
import com.bruno.kota.repositories.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Sem isso, ninguém consegue logar na primeira vez que o sistema sobe — não existe
// fluxo de "criar minha conta" pro admin, então alguém precisa existir antes de
// qualquer login ser possível. Só cria se NENHUM admin existir ainda, então rodar de
// novo em produção não recria nem sobrescreve nada.
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String DEFAULT_EMAIL = "admin@kota.com";
    private static final String DEFAULT_PASSWORD = "admin123";

    @Override
    public void run(String... args) {
        boolean adminExists = userRepository.findByEmail(DEFAULT_EMAIL).isPresent();
        if (adminExists) {
            return;
        }

        User admin = User.builder()
                .email(DEFAULT_EMAIL)
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(UserRole.ADMIN)
                .enabled(true)
                .build();
        userRepository.save(admin);

        log.warn("=========================================================");
        log.warn("Usuário admin criado automaticamente por não existir nenhum:");
        log.warn("  e-mail: {}", DEFAULT_EMAIL);
        log.warn("  senha:  {}", DEFAULT_PASSWORD);
        log.warn("TROQUE ESSA SENHA assim que fizer o primeiro login.");
        log.warn("=========================================================");
    }
}
