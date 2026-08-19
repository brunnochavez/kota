package com.bruno.kota.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bruno.kota.entities.CompanySettings;

public interface CompanySettingsRepository extends JpaRepository<CompanySettings, Long> {
}
