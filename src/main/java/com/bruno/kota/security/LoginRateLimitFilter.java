package com.bruno.kota.security;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// Segunda camada de proteção contra força bruta, complementar ao bloqueio por CONTA do
// AuthService: aqui o limite é por ORIGEM da tentativa (IP), então pega o caso de alguém
// tentando MUITOS e-mails diferentes a partir do mesmo lugar — cenário que o bloqueio por
// conta sozinho não cobre, já que cada e-mail tem seu próprio contador.
//
// Em memória de propósito — não precisa sobreviver a restart nem ser compartilhado entre
// instâncias (o kota roda uma instância só, sem load balancer). Um Map simples com limpeza
// preguiçosa por janela de tempo já resolve, sem precisar de Redis pra isso.
@Component
@Slf4j
public class LoginRateLimitFilter extends OncePerRequestFilter {

    // Generoso de propósito — representantes de um mesmo escritório/loja podem logar a
    // partir do mesmo IP em sequência, isso não pode ser tratado como ataque. O que isso
    // pega é volume muito acima do uso normal (script tentando dezenas de e-mails).
    private static final int MAX_ATTEMPTS_PER_WINDOW = 20;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        boolean isLoginAttempt = "POST".equalsIgnoreCase(request.getMethod())
                && "/auth/login".equals(request.getRequestURI());

        if (!isLoginAttempt) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket());

        if (bucket.registerAttemptAndCheckLimit()) {
            log.warn("Rate limit de login excedido para IP {}", ip);
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    // Nginx está na frente (ver kota-deploy-contexto), repassando X-Real-IP/X-Forwarded-For
    // — sem isso, request.getRemoteAddr() sempre devolveria o IP interno do proxy, não do
    // visitante de verdade, e todo mundo cairia no mesmo balde.
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "timestamp", java.time.LocalDateTime.now().toString(),
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "message", "Muitas tentativas de login. Aguarde alguns minutos antes de tentar de novo."
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    // Limpeza periódica — sem isso, IPs que só apareceram uma vez ficam ocupando memória
    // pra sempre. Roda pouco (a cada 10min), não precisa ser agressivo.
    @Scheduled(fixedRate = 600_000)
    public void cleanupExpiredBuckets() {
        Instant cutoff = Instant.now().minus(WINDOW);
        buckets.entrySet().removeIf(entry -> entry.getValue().windowStart.isBefore(cutoff));
    }

    private static class Bucket {
        private volatile Instant windowStart = Instant.now();
        private final AtomicInteger count = new AtomicInteger(0);

        // true = estourou o limite dessa janela. Reinicia a janela sozinho quando o
        // tempo passa, sem precisar de nenhum job externo pra "resetar" o balde.
        synchronized boolean registerAttemptAndCheckLimit() {
            Instant now = Instant.now();
            if (Duration.between(windowStart, now).compareTo(WINDOW) > 0) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() > MAX_ATTEMPTS_PER_WINDOW;
        }
    }
}
