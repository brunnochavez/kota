package com.bruno.kota.controllers;

import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bruno.kota.dtos.CompanyEmailContactRequest;
import com.bruno.kota.dtos.CompanyEmailContactResponse;
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

    // Contatos que recebem o PDF de resultado automaticamente quando uma cotação fecha
    // (ver QuotationService.notifyInternalContactsOfClose) — gerenciados na tela "Dados
    // da Empresa". ADMIN só: e-mail de terceiro (nome + endereço) é dado que só o
    // administrador deveria poder cadastrar ou remover.
    @GetMapping("/email-contacts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<CompanyEmailContactResponse> listEmailContacts() {
        return companySettingsService.listEmailContacts();
    }

    @PostMapping("/email-contacts")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyEmailContactResponse addEmailContact(@Valid @RequestBody CompanyEmailContactRequest request) {
        return companySettingsService.addEmailContact(request);
    }

    @DeleteMapping("/email-contacts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteEmailContact(@PathVariable Long id) {
        companySettingsService.deleteEmailContact(id);
        return ResponseEntity.noContent().build();
    }

    private MediaType guessContentType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return MediaType.IMAGE_PNG;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return MediaType.IMAGE_JPEG;
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return MediaType.IMAGE_GIF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
