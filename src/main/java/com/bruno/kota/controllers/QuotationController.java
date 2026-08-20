package com.bruno.kota.controllers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.bruno.kota.dtos.QuotationReportRow;
import com.bruno.kota.dtos.QuotationUpdateRequest;
import com.bruno.kota.dtos.RepresentativePerformance;
import com.bruno.kota.dtos.QuotationFillRate;
import com.bruno.kota.dtos.ReorderPointRow;
import com.bruno.kota.dtos.ReviewBatchUpdateRequest;
import com.bruno.kota.dtos.SalesProjectionUpdateRequest;
import com.bruno.kota.dtos.WonQuotationSummary;
import com.bruno.kota.security.AuthPrincipal;
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
    // Spring, mesmo declarado depois — mas deixei antes por clareza pra quem for ler.s
    @GetMapping("/representative-fill-rate")
    @PreAuthorize("hasRole('ADMIN')")
    public List<QuotationFillRate> getRepresentativeFillRate() {
        return quotationService.getRepresentativeFillRate();
    }

    @GetMapping("/won")
    public List<WonQuotationSummary> findWonQuotations(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam Long supplierId) {
        return quotationService.findWonQuotations(supplierId, repIdOrNull(principal));
    }

    @GetMapping("/pending-fulfillment")
    public List<WonQuotationSummary> findPendingFulfillmentResults(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam Long supplierId) {
        return quotationService.findPendingFulfillmentResults(supplierId, repIdOrNull(principal));
    }

    @GetMapping("/performance")
    public RepresentativePerformance getRepresentativePerformance(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam Long supplierId) {
        return quotationService.getRepresentativePerformance(supplierId, repIdOrNull(principal));
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('ADMIN')")
    public List<QuotationReportRow> getQuotationReport(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) String product,
            @RequestParam(defaultValue = "true") boolean onlyWinners
    ) {
        LocalDateTime fromDt = (from != null && !from.isBlank()) ? LocalDate.parse(from).atStartOfDay() : null;
        LocalDateTime toDt = (to != null && !to.isBlank()) ? LocalDate.parse(to).atTime(23, 59, 59) : null;
        return quotationService.getQuotationReport(fromDt, toDt, supplierId, representativeId, product, onlyWinners);
    }

    @GetMapping("/reorder-points")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReorderPointRow> getReorderPointReport() {
        return quotationService.getReorderPointReport();
    }

    @GetMapping("/{id}/my-bids")
    public List<QuotationReportRow> getMyBidsForQuotation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id, @RequestParam Long supplierId) {
        return quotationService.getMyBidsForQuotation(id, supplierId, repIdOrNull(principal));
    }

    @PostMapping("/{id}/items/{itemId}/cut")
    public ResponseEntity<Void> cutFulfillmentItem(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id, @PathVariable Long itemId) {
        quotationService.cutFulfillmentItem(id, itemId, repIdOrNull(principal));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Void> declineQuotation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id, @RequestParam Long supplierId) {
        quotationService.declineQuotation(id, supplierId, repIdOrNull(principal));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/finalize-fulfillment")
    public ResponseEntity<Void> finalizeFulfillment(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id, @RequestParam Long supplierId) {
        quotationService.finalizeFulfillment(id, supplierId, repIdOrNull(principal));
        return ResponseEntity.noContent().build();
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuotationResponse> createManually(@Valid @RequestBody QuotationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.createManually(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationResponse update(@PathVariable Long id, @Valid @RequestBody QuotationUpdateRequest request) {
        return quotationService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationResponse publish(@PathVariable Long id) {
        return quotationService.publish(id);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationResponse duplicate(@PathVariable Long id) {
        return quotationService.duplicate(id);
    }

    @PostMapping("/{id}/duplicate-unquoted-items")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationResponse duplicateUnquotedItems(@PathVariable Long id) {
        return quotationService.duplicateUnquotedItems(id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationCloseResult close(@PathVariable Long id, @RequestBody(required = false) QuotationCloseRequest request) {
        QuotationCloseRequest safeRequest = request != null ? request : new QuotationCloseRequest(null, null, null);
        return quotationService.close(id, safeRequest);
    }

    @PostMapping("/{id}/confirm-close")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationCloseResult confirmClose(@PathVariable Long id, @RequestBody(required = false) ConfirmCloseRequest request) {
        return quotationService.confirmClose(id, request);
    }

    @GetMapping("/{id}/result-pdf")
    public ResponseEntity<byte[]> exportResultPdf(@PathVariable Long id, @RequestParam(required = false) Long supplierId) {
        byte[] pdf = supplierId != null
                ? quotationPdfService.generateSupplierResultPdf(id, supplierId)
                : quotationPdfService.generateResultPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cotacao-" + id + ".pdf")
                .body(pdf);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationImportResult importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam(required = false) Long supplierGroupId,
            @RequestParam(required = false) String expirationDate,
            @RequestParam(required = false) Integer defaultSalesProjectionDays,
            @RequestParam(required = false) Integer descriptionColumn,
            @RequestParam(required = false) Integer barcodeColumn,
            @RequestParam(required = false) Integer quantityColumn) {
        return quotationImportService.importFile(
                file, name, supplierGroupId, expirationDate, defaultSalesProjectionDays,
                descriptionColumn, barcodeColumn, quantityColumn);
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationItemResponse addItem(@PathVariable Long id, @Valid @RequestBody QuotationItemCreateRequest request) {
        return quotationService.addItem(id, request);
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationItemResponse updateItem(
            @PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody QuotationItemUpdateRequest request) {
        return quotationService.updateItemQuantity(id, itemId, request.quantity());
    }

    @PutMapping("/{id}/items/{itemId}/sales-projection")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationItemResponse updateItemSalesProjection(
            @PathVariable Long id, @PathVariable Long itemId, @RequestBody SalesProjectionUpdateRequest request) {
        return quotationService.updateItemSalesProjection(id, itemId, request.salesProjectionDays());
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        quotationService.removeItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items/{itemId}/assign-winner")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationItemResponse assignManualWinner(
            @PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody ManualWinnerAssignRequest request) {
        return quotationService.assignManualWinner(id, itemId, request);
    }

    @PostMapping("/{id}/items/add-with-winner")
    @PreAuthorize("hasRole('ADMIN')")
    public QuotationItemResponse addItemWithWinner(
            @PathVariable Long id, @Valid @RequestBody AddItemWithWinnerRequest request) {
        return quotationService.addItemWithWinner(id, request);
    }

    @PostMapping("/{id}/review-batch-update")
    @PreAuthorize("hasRole('ADMIN')")
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

    // null = admin (sem restrição de posse); id do representante = restringe aos
    // fornecedores que ele de fato representa, validado dentro do service.
    private Long repIdOrNull(AuthPrincipal principal) {
        return (principal != null && !principal.isAdmin()) ? principal.representativeId() : null;
    }
}