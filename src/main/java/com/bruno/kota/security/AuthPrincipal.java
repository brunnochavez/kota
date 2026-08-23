package com.bruno.kota.security;

// Isso é o que fica disponível em cada requisição depois que o JwtAuthenticationFilter
// valida o token — representativeId só vem preenchido pra usuário com role REPRESENTATIVE
// (admin não tem representante associado). Os services usam isso pra saber "em nome de
// quem" agir, em vez de confiar em qualquer id que o cliente mande no corpo da requisição.
public record AuthPrincipal(
        Long userId,
        String email,
        String role,
        Long representativeId,
        String name,
        String impersonatedBy
) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    // Usado em todo lugar que precisa mostrar "quem fez a ação" (ex: performedBy no
    // histórico da cotação) — nome se tiver sido cadastrado, e-mail como fallback pra
    // contas ADMIN antigas (o admin@kota.com do AdminBootstrap, ou qualquer uma criada
    // antes do campo "nome" existir em Usuários Administradores).
    public String displayName() {
        return (name != null && !name.isBlank()) ? name : email;
    }

    public boolean isImpersonated() {
        return impersonatedBy != null && !impersonatedBy.isBlank();
    }
}
