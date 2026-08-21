package com.bruno.kota.security;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

// Regra geral: tudo exige login válido, com duas exceções (páginas estáticas e o próprio
// endpoint de login). Quem precisa ser ADMIN especificamente fica marcado direto no
// controller com @PreAuthorize("hasRole('ADMIN')") — mais fácil de auditar método por
// método do que tentar reconstruir a URL de cada rota aqui num bloco só.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // API stateless com JWT — não existe cookie de sessão aqui, então o
                // ataque que CSRF protege (usar cookie de sessão de outra aba) não se
                // aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/*.html", "/favicon.ico", "/img/**", "/js/**", "/auth/login", "/company-settings/logo").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/company-settings").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Sem isso, "sem token" e "logado mas sem permissão" voltavam com o
                // MESMO 403 vazio (sem corpo) — impossível distinguir os dois casos só
                // olhando a resposta. Agora sem token/token inválido = 401 com mensagem
                // clara; logado mas papel errado = 403 com mensagem clara.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpStatus.UNAUTHORIZED, "Faça login para continuar."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN, "Você não tem permissão para fazer isso."))
                );

        return http.build();
    }

    private void writeJsonError(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
