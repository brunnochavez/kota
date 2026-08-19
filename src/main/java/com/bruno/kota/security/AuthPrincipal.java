package com.bruno.kota.security;

// Isso é o que fica disponível em cada requisição depois que o JwtAuthenticationFilter
// valida o token — representativeId só vem preenchido pra usuário com role REPRESENTATIVE
// (admin não tem representante associado). Os services usam isso pra saber "em nome de
// quem" agir, em vez de confiar em qualquer id que o cliente mande no corpo da requisição.
public record AuthPrincipal(
        Long userId,
        String email,
        String role,
        Long representativeId
) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
