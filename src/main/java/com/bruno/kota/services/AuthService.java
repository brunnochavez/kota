package com.bruno.kota.services;

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

    private final UserRepository userRepository;
    private final RepresentativeRepository representativeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // Mensagem de erro é a MESMA pra "email não existe" e "senha errada" — de propósito,
    // pra não dar pista de quais emails têm conta cadastrada no sistema.
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new BusinessRuleException("E-mail ou senha inválidos."));

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessRuleException("Esse acesso está desativado. Fale com o administrador.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessRuleException("E-mail ou senha inválidos.");
        }

        String name = user.getEmail();
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
