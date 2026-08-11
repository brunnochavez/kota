package com.bruno.kota.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.SupplierGroup;

public interface SupplierGroupRepository extends JpaRepository<SupplierGroup, Long> {
    Optional<SupplierGroup> findByName(String name);

    @Query(value = "SELECT * FROM supplier_groups WHERE name = :name", nativeQuery = true)
    Optional<SupplierGroup> findByNameIncludingDeleted(@Param("name") String name);

    @Query(value = "SELECT * FROM supplier_groups WHERE id = :id", nativeQuery = true)
    Optional<SupplierGroup> findByIdIncludingDeleted(@Param("id") Long id);
}