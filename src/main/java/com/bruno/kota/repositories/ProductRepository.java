package com.bruno.kota.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bruno.kota.entities.Product;
import com.bruno.kota.entities.ProductGroup;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByBarcode(String barcode);

    Page<Product> findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String name, String barcode, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE :group MEMBER OF p.groups")
    List<Product> findByGroup(@Param("group") ProductGroup group);

    @Query(value = "SELECT * FROM products WHERE barcode = :barcode", nativeQuery = true)
    Optional<Product> findByBarcodeIncludingDeleted(@Param("barcode") String barcode);

    @Query(value = "SELECT * FROM products WHERE id = :id", nativeQuery = true)
    Optional<Product> findByIdIncludingDeleted(@Param("id") Long id);

    @Query(value = "SELECT * FROM products WHERE deleted = true", nativeQuery = true)
    List<Product> findAllInactive();
}