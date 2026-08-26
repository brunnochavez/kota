package com.bruno.kota.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "import_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "description_column", nullable = false)
    private Integer descriptionColumn;

    @Column(name = "barcode_column", nullable = false)
    private Integer barcodeColumn;

    @Column(name = "quantity_column", nullable = false)
    private Integer quantityColumn;

    // Opcional — só é preenchida quando alguma importação já mapeou uma coluna de custo
    // pra esse layout de cabeçalho. Diferente das outras três colunas, nunca bloqueia a
    // reutilização automática do perfil: uma importação que NÃO quer custo (ver
    // includeCostPrices) simplesmente ignora esse campo, mesmo que ele esteja preenchido.
    @Column(name = "cost_column")
    private Integer costColumn;

    @Column(name = "header_signature", nullable = false, length = 1000)
    private String headerSignature;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}