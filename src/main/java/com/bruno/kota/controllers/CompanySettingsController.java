package com.bruno.kota.controllers;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bruno.kota.dtos.CompanySettingsRequest;
import com.bruno.kota.dtos.CompanySettingsResponse;
import com.bruno.kota.services.CompanySettingsService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/company-settings")
@RequiredArgsConstructor
public class CompanySettingsController {

    private final CompanySettingsService companySettingsService;

    @GetMapping
    public CompanySettingsResponse get() {
        return companySettingsService.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CompanySettingsResponse update(@Valid @RequestBody CompanySettingsRequest request) {
        return companySettingsService.update(request);
    }

    @PostMapping("/logo")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanySettingsResponse uploadLogo(@RequestParam("file") MultipartFile file) {
        return companySettingsService.uploadLogo(file);
    }

    // Sem restrição de login de propósito — o cabeçalho do admin.html precisa exibir a
    // imagem via <img src="...">, e essa tag não manda cabeçalho Authorization (mesmo
    // motivo do PDF, só que ali resolvi com fetch+blob; aqui é mais simples deixar
    // público mesmo, já que logo de empresa não é dado sensível).
    @GetMapping("/logo")
    public ResponseEntity<ByteArrayResource> getLogo() {
        byte[] bytes = companySettingsService.readLogoBytes();
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(guessContentType(bytes))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new ByteArrayResource(bytes));
    }

    private MediaType guessContentType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return MediaType.IMAGE_PNG;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return MediaType.IMAGE_JPEG;
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return MediaType.IMAGE_GIF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
