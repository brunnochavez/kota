package com.bruno.kota.controllers;

import java.util.List;

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

import com.bruno.kota.dtos.BidAdminUpdateRequest;
import com.bruno.kota.dtos.BidRequest;
import com.bruno.kota.dtos.BidResponse;
import com.bruno.kota.security.AuthPrincipal;
import com.bruno.kota.services.BidService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    @GetMapping
    public List<BidResponse> findByQuotationItem(@RequestParam Long quotationItemId) {
        return bidService.findByQuotationItem(quotationItemId);
    }

    @PostMapping
    public ResponseEntity<BidResponse> submit(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody BidRequest request) {
        Long authenticatedRepresentativeId = (principal != null && !principal.isAdmin()) ? principal.representativeId() : null;
        return ResponseEntity.ok(bidService.submit(request, authenticatedRepresentativeId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BidResponse updateByAdmin(@PathVariable Long id, @Valid @RequestBody BidAdminUpdateRequest request) {
        return bidService.updateByAdmin(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteByAdmin(@PathVariable Long id) {
        bidService.deleteByAdmin(id);
        return ResponseEntity.noContent().build();
    }
}