package com.bruno.kota.entities;

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

// Diferente de Supplier/Representative, isso nunca é uma lista — só existe UMA empresa
// usando o sistema. Em vez de complicar com um padrão "sempre pega o id=1" espalhado
// pelo código, o service resolve isso sozinho (cria a primeira linha na primeira vez
// que alguém pedir, se ainda não existir nenhuma).
@Entity
@Table(name = "company_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 14)
    private String cnpj;

    private String address;

    private String neighborhood;

    private String city;

    @Column(length = 2)
    private String state;

    @Column(name = "zip_code", length = 9)
    private String zipCode;

    private String email;

    @Column(name = "state_registration")
    private String stateRegistration;

    private String phone;

    // Guarda só o NOME do arquivo (ex: "logo-a1b2c3.png"), não o caminho inteiro — o
    // diretório real vem de configuração (app.upload-dir), pra não prender o caminho
    // físico do disco dentro do banco.
    @Column(name = "logo_filename")
    private String logoFilename;
}
