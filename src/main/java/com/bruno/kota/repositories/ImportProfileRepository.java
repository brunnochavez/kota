package com.bruno.kota.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.ImportProfile;

public interface ImportProfileRepository extends JpaRepository<ImportProfile, Long> {
    Optional<ImportProfile> findTopByOrderByUpdatedAtDesc();
}