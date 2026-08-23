package com.bruno.kota.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    // Nome de exibição — hoje só preenchido pra usuário ADMIN criado via "Usuários
    // Administradores" (AdminUserController). Representante não usa esse campo: o nome
    // dele já vive em Representative.name, associado via User.representative. Fica
    // nullable porque contas ADMIN antigas (o admin@kota.com do AdminBootstrap, e
    // qualquer uma criada antes desse campo existir) não têm nome ainda — nesses casos
    // o histórico da cotação e o resto da UI caem de volta pro e-mail.
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    // true sempre que a senha foi definida por OUTRA pessoa (admin criando acesso, ou
    // resetando) — nesses casos, o admin chegou a saber a senha em algum momento, então
    // ela precisa ser trocada por uma que só o próprio dono conhece antes de valer pra
    // valer. Fica false assim que o próprio usuário troca via /auth/change-password.
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = true;

    // Contador de senha errada seguida — zera a cada login bem-sucedido. Junto com
    // lockedUntil, é o mecanismo de bloqueio contra força bruta (ver AuthService.login).
    // columnDefinition com DEFAULT 0 explícito é necessário aqui: users já tem linhas
    // existentes em produção, e o MySQL em modo estrito recusa ALTER TABLE ADD COLUMN
    // NOT NULL sem um valor padrão pra preencher as linhas que já existem.
    @Column(name = "failed_login_attempts", nullable = false, columnDefinition = "INTEGER NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    // Null enquanto a conta não está bloqueada. Quando failedLoginAttempts bate o limite,
    // isso vira "agora + N minutos" — login fica recusado até passar dessa data, mesmo
    // com a senha certa, sem precisar apagar o histórico de tentativas antes da hora.
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;
}