package com.bruno.kota.repositories;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import com.bruno.kota.entities.Supplier;
import com.bruno.kota.entities.SupplierGroup;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    Optional<Supplier> findByCnpj(String cnpj);

    List<Supplier> findByRepresentativeId(Long representativeId);

    boolean existsByRepresentativeId(Long representativeId);

    @Query("SELECT s FROM Supplier s WHERE :group MEMBER OF s.groups")
    List<Supplier> findByGroup(@Param("group") SupplierGroup group);

    @Query(value = "SELECT * FROM suppliers WHERE cnpj = :cnpj", nativeQuery = true)
    Optional<Supplier> findByCnpjIncludingDeleted(@Param("cnpj") String cnpj);

    @Query(value = "SELECT * FROM suppliers WHERE id = :id", nativeQuery = true)
    Optional<Supplier> findByIdIncludingDeleted(@Param("id") Long id);

    @Query(value = "SELECT * FROM suppliers WHERE deleted = true", nativeQuery = true)
    List<Supplier> findAllInactive();

    // Bulk DELETE nativo — @SQLDelete na entidade Supplier só intercepta remoção via
    // entidade gerenciada (repository.delete(entity)); um DELETE em SQL puro passa por
    // baixo disso de propósito, pra permitir excluir DE VERDADE quando o fornecedor
    // nunca foi usado (ver SupplierService.hardDelete).
    @Modifying
    @Query(value = "DELETE FROM suppliers WHERE id = :id", nativeQuery = true)
    void hardDeleteById(@Param("id") Long id);
}