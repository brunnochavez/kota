package com.bruno.kota.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);

    // Chamado antes de emitir um token novo pro mesmo usuário — nunca deixa mais de um
    // link válido circulando ao mesmo tempo (o antigo, se ainda não foi clicado, para
    // de funcionar assim que um novo é pedido).
    void deleteByUserId(Long userId);
}
