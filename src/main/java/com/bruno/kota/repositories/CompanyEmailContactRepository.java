package com.bruno.kota.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.CompanyEmailContact;

public interface CompanyEmailContactRepository extends JpaRepository<CompanyEmailContact, Long> {
}
