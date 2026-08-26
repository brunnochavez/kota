package com.bruno.kota.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.dtos.ImportProfileResponse;
import com.bruno.kota.entities.ImportProfile;
import com.bruno.kota.exceptions.ResourceNotFoundException;
import com.bruno.kota.repositories.ImportProfileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImportProfileService {

    private final ImportProfileRepository importProfileRepository;

    @Transactional(readOnly = true)
    public ImportProfileResponse findCurrent() {
        ImportProfile profile = importProfileRepository.findTopByOrderByUpdatedAtDesc()
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum perfil de importação foi definido ainda"));
        return toResponse(profile);
    }

    private ImportProfileResponse toResponse(ImportProfile profile) {
        return new ImportProfileResponse(
                profile.getId(),
                profile.getDescriptionColumn(),
                profile.getBarcodeColumn(),
                profile.getQuantityColumn(),
                profile.getCostColumn(),
                profile.getHeaderSignature()
        );
    }
}