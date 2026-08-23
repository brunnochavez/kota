package com.bruno.kota.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bruno.kota.entities.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationHours;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-hours}") long expirationHours
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
    }

    // O token carrega tudo que o filtro precisa pra montar o AuthPrincipal sem bater no
    // banco a cada requisição — id do usuário, papel, e o id do representante (só pra
    // quem tem um vinculado). representativeId fica de fora do token pra admin.
    public String generateToken(User user, Long representativeId) {
        return generateToken(user, representativeId, null);
    }

    // impersonatedBy: só preenchido no fluxo de "ver como esse representante" (admin
    // vendo a tela do representante sem saber a senha dele) — deixa uma marca dentro do
    // próprio token de quem gerou aquela sessão em nome de outra pessoa, útil se algo
    // precisar ser auditado depois. null no login normal.
    public String generateToken(User user, Long representativeId, String impersonatedBy) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationHours * 3600)));

        if (user.getName() != null && !user.getName().isBlank()) {
            builder.claim("name", user.getName());
        }
        if (representativeId != null) {
            builder.claim("representativeId", representativeId);
        }
        if (impersonatedBy != null && !impersonatedBy.isBlank()) {
            builder.claim("impersonatedBy", impersonatedBy);
        }

        return builder.signWith(key).compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
