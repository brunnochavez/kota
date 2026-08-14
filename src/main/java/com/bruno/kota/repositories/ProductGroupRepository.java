package com.bruno.kota.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.ProductGroup;

public interface ProductGroupRepository extends JpaRepository<ProductGroup, Long> {
    Optional<ProductGroup> findByName(String name);

    @Query(value = "SELECT * FROM product_groups WHERE name = :name", nativeQuery = true)
    Optional<ProductGroup> findByNameIncludingDeleted(@Param("name") String name);

    @Query(value = "SELECT * FROM product_groups WHERE id = :id", nativeQuery = true)
    Optional<ProductGroup> findByIdIncludingDeleted(@Param("id") Long id);
}
