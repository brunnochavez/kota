package com.bruno.kota.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

// Roda ANTES de qualquer controller. Se o cabeçalho Authorization trouxer um token válido,
// monta o AuthPrincipal e deixa disponível pro resto da requisição via
// @AuthenticationPrincipal. Token ausente ou inválido não dá erro aqui — só segue sem
// autenticar, e o SecurityConfig é quem decide se essa rota exige login ou não.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parseClaims(token);

                Long userId = Long.valueOf(claims.getSubject());
                String email = claims.get("email", String.class);
                String role = claims.get("role", String.class);
                String name = claims.get("name", String.class);
                String impersonatedBy = claims.get("impersonatedBy", String.class);

                // Número em claim de JWT às vezes vem desserializado como Integer, não
                // Long — pedir Long.class direto pode estourar ClassCastException.
                // Passar por toString() evita esse problema de vez.
                Object repIdClaim = claims.get("representativeId");
                Long representativeId = repIdClaim != null ? Long.valueOf(repIdClaim.toString()) : null;

                AuthPrincipal principal = new AuthPrincipal(userId, email, role, representativeId, name, impersonatedBy);
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
