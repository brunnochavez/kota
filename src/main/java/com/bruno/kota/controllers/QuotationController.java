package com.bruno.kota.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.bruno.kota.dtos.AddItemWithWinnerRequest;
import com.bruno.kota.dtos.ConfirmCloseRequest;
import com.bruno.kota.dtos.ManualWinnerAssignRequest;
import com.bruno.kota.dtos.QuotationCloseRequest;
import com.bruno.kota.dtos.QuotationCloseResult;
import com.bruno.kota.dtos.QuotationCreateRequest;
import com.bruno.kota.dtos.QuotationImportResult;
import com.bruno.kota.dtos.QuotationItemCreateRequest;
import com.bruno.kota.dtos.QuotationItemResponse;
import com.bruno.kota.dtos.QuotationItemUpdateRequest;
import com.bruno.kota.dtos.QuotationResponse;
import com.bruno.kota.dtos.QuotationUpdateRequest;
import com.bruno.kota.dtos.QuotationFillRate;
import com.bruno.kota.dtos.ReviewBatchUpdateRequest;
import com.bruno.kota.services.QuotationImportService;
import com.bruno.kota.services.QuotationPdfService;
import com.bruno.kota.services.QuotationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/quotations")
@RequiredArgsConstructor
public class QuotationController {

    private final QuotationService quotationService;
    private final QuotationPdfService quotationPdfService;
    private final QuotationImportService quotationImportService;

    @GetMapping
    public List<QuotationResponse> findAll() {
        return quotationService.findAll();
    }

    // Path literal ("representative-fill-rate") sempre vence sobre /{id} no roteamento do
    // Spring, mesmo declarado depois — mas deixei antes por clareza pra quem for ler.
    @GetMapping("/representative-fill-rate")
    public List<QuotationFillRate> getRepresentativeFillRate() {
        return quotationService.getRepresentativeFillRate();
    }

    @GetMapping("/{id}")
    public QuotationResponse findById(@PathVariable Long id) {
        return quotationService.findById(id);
    }

    @GetMapping("/{id}/items")
    public List<QuotationItemResponse> findItems(@PathVariable Long id) {
        return quotationService.findItems(id);
    }

    @PostMapping
    public ResponseEntity<QuotationResponse> createManually(@Valid @RequestBody QuotationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.createManually(request));
    }

    @PutMapping("/{id}")
    public QuotationResponse update(@PathVariable Long id, @Valid @RequestBody QuotationUpdateRequest request) {
        return quotationService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public QuotationResponse publish(@PathVariable Long id) {
        return quotationService.publish(id);
    }

    @PostMapping("/{id}/close")
    public QuotationCloseResult close(@PathVariable Long id, @RequestBody(required = false) QuotationCloseRequest request) {
        QuotationCloseRequest safeRequest = request != null ? request : new QuotationCloseRequest(null, null, null);
        return quotationService.close(id, safeRequest);
    }

    @PostMapping("/{id}/confirm-close")
    public QuotationCloseResult confirmClose(@PathVariable Long id, @RequestBody(required = false) ConfirmCloseRequest request) {
        return quotationService.confirmClose(id, request);
    }

    @GetMapping("/{id}/result-pdf")
    public ResponseEntity<byte[]> exportResultPdf(@PathVariable Long id) {
        byte[] pdf = quotationPdfService.generateResultPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cotacao-" + id + ".pdf")
                .body(pdf);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuotationImportResult importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam(required = false) Long supplierGroupId,
            @RequestParam(required = false) String expirationDate,
            @RequestParam(required = false) Integer descriptionColumn,
            @RequestParam(required = false) Integer barcodeColumn,
            @RequestParam(required = false) Integer quantityColumn) {
        return quotationImportService.importFile(
                file, name, supplierGroupId, expirationDate, descriptionColumn, barcodeColumn, quantityColumn);
    }

    @PostMapping("/{id}/items")
    public QuotationItemResponse addItem(@PathVariable Long id, @Valid @RequestBody QuotationItemCreateRequest request) {
        return quotationService.addItem(id, request);
    }

    @PutMapping("/{id}/items/{itemId}")
    public QuotationItemResponse updateItem(
            @PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody QuotationItemUpdateRequest request) {
        return quotationService.updateItemQuantity(id, itemId, request.quantity());
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        quotationService.removeItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items/{itemId}/assign-winner")
    public QuotationItemResponse assignManualWinner(
            @PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody ManualWinnerAssignRequest request) {
        return quotationService.assignManualWinner(id, itemId, request);
    }

    @PostMapping("/{id}/items/add-with-winner")
    public QuotationItemResponse addItemWithWinner(
            @PathVariable Long id, @Valid @RequestBody AddItemWithWinnerRequest request) {
        return quotationService.addItemWithWinner(id, request);
    }

    @PostMapping("/{id}/review-batch-update")
    public ResponseEntity<Void> applyReviewBatchUpdate(
            @PathVariable Long id, @RequestBody ReviewBatchUpdateRequest request) {
        int attempts = 0;
        while (true) {
            try {
                quotationService.applyReviewBatchUpdate(id, request);
                return ResponseEntity.noContent().build();
            } catch (org.springframework.dao.ConcurrencyFailureException ex) {
                attempts++;
                if (attempts >= 3) {
                    throw ex;
                }
                try {
                    Thread.sleep(150L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}