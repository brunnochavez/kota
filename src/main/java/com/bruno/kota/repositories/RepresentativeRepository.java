package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.Representative;

public interface RepresentativeRepository extends JpaRepository<Representative, Long> {

    Optional<Representative> findByCpf(String cpf);

    @Query(value = "SELECT * FROM representatives WHERE cpf = :cpf", nativeQuery = true)
    Optional<Representative> findByCpfIncludingDeleted(@Param("cpf") String cpf);

    @Query(value = "SELECT * FROM representatives WHERE id = :id", nativeQuery = true)
    Optional<Representative> findByIdIncludingDeleted(@Param("id") Long id);

    @Query(value = "SELECT * FROM representatives WHERE deleted = true", nativeQuery = true)
    List<Representative> findAllInactive();
}