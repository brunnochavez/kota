package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.User;
import com.bruno.kota.entities.UserRole;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    // Usado pela tela "Usuários Administradores" — lista quem tem acesso completo ao
    // painel, além do admin criado pelo AdminBootstrap na primeira subida do sistema.
    List<User> findByRole(UserRole role);
}