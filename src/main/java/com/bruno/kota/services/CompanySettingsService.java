package com.bruno.kota.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bruno.kota.dtos.CompanySettingsRequest;
import com.bruno.kota.dtos.CompanySettingsResponse;
import com.bruno.kota.entities.CompanySettings;
import com.bruno.kota.exceptions.BusinessRuleException;
import com.bruno.kota.repositories.CompanySettingsRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CompanySettingsService {

    private final CompanySettingsRepository companySettingsRepository;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    // Só existe uma linha. Se ninguém nunca configurou nada ainda, cria uma com nome
    // placeholder na hora — assim o resto do sistema (PDF, WhatsApp, cabeçalho) sempre
    // tem alguma coisa pra ler, em vez de precisar tratar "ainda não existe" em cada
    // lugar que usa isso.
    // REQUIRES_NEW de propósito: essa é a única escrita que pode acontecer "por baixo dos
    // panos" de um método que parecia ser só leitura (ex: gerar PDF chama isso indireto
    // via readLogoBytes()). Com a propagação padrão (REQUIRED), esse INSERT herdaria o
    // readOnly=true de quem chamou e o banco rejeitaria a escrita — foi exatamente esse
    // bug que aconteceu na primeira carga do dashboard. REQUIRES_NEW garante uma
    // transação própria, sempre gravável, não importa de onde isso for chamado.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompanySettings getOrCreate() {
        return companySettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> companySettingsRepository.save(
                        CompanySettings.builder().name("Minha Empresa").build()));
    }

    @Transactional
    public CompanySettingsResponse get() {
        return toResponse(getOrCreate());
    }

    @Transactional
    public CompanySettingsResponse update(CompanySettingsRequest request) {
        CompanySettings settings = getOrCreate();
        settings.setName(request.name());
        settings.setCnpj(request.cnpj());
        settings.setStateRegistration(request.stateRegistration());
        settings.setEmail(request.email());
        settings.setPhone(request.phone());
        settings.setAddress(request.address());
        settings.setNeighborhood(request.neighborhood());
        settings.setCity(request.city());
        settings.setState(request.state());
        settings.setZipCode(request.zipCode());
        return toResponse(companySettingsRepository.save(settings));
    }

    @Transactional
    public CompanySettingsResponse uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Nenhum arquivo enviado.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessRuleException("O arquivo precisa ser uma imagem.");
        }

        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);

            String extension = "";
            String originalName = file.getOriginalFilename();
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
            }
            // Lista fechada de extensões aceitas, em vez de aceitar qualquer sufixo que
            // vier no nome do arquivo enviado pelo cliente. Antes disso, um nome de
            // arquivo malicioso (ex: contendo "../../" na parte tratada como extensão)
            // ia direto pro Path.resolve() sem filtro — teoricamente dava pra escrever
            // fora da pasta de uploads. contentType já é checado acima, mas o
            // Content-Type também é enviado pelo próprio cliente (spoofável); a extensão
            // final do arquivo salvo no disco não pode depender só disso.
            if (!extension.toLowerCase().matches("\\.(png|jpe?g|gif|webp|svg)")) {
                extension = ".png";
            }
            // Nome único a cada upload (não sobrescreve o arquivo antigo com o mesmo
            // nome) — evita o navegador continuar mostrando a logo antiga em cache
            // depois de trocada.
            String filename = "logo-" + UUID.randomUUID() + extension;
            Path target = dir.resolve(filename);
            Files.copy(file.getInputStream(), target);

            CompanySettings settings = getOrCreate();
            String oldFilename = settings.getLogoFilename();
            settings.setLogoFilename(filename);
            companySettingsRepository.save(settings);

            if (oldFilename != null) {
                Files.deleteIfExists(dir.resolve(oldFilename));
            }

            return toResponse(settings);
        } catch (IOException e) {
            throw new BusinessRuleException("Erro ao salvar a imagem: " + e.getMessage());
        }
    }

    // Usado tanto pelo endpoint que serve a imagem (GET /company-settings/logo) quanto
    // pelo QuotationPdfService, que precisa dos bytes crus pra embutir no PDF.
    public byte[] readLogoBytes() {
        CompanySettings settings = getOrCreate();
        if (settings.getLogoFilename() == null) {
            return null;
        }
        try {
            Path path = Path.of(uploadDir).resolve(settings.getLogoFilename());
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    private CompanySettingsResponse toResponse(CompanySettings settings) {
        String logoUrl = settings.getLogoFilename() != null ? "/company-settings/logo" : null;
        return new CompanySettingsResponse(
                settings.getId(), settings.getName(), settings.getCnpj(), settings.getStateRegistration(),
                settings.getEmail(), settings.getPhone(), settings.getAddress(), settings.getNeighborhood(),
                settings.getCity(), settings.getState(), settings.getZipCode(), logoUrl
        );
    }
}
