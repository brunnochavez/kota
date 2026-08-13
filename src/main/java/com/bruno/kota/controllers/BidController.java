package com.bruno.kota.controllers;
import java.util.List;
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
import com.bruno.kota.dtos.BidAdminUpdateRequest;
import com.bruno.kota.dtos.BidRequest;
import com.bruno.kota.dtos.BidResponse;
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
    public ResponseEntity<BidResponse> submit(@Valid @RequestBody BidRequest request) {
        return ResponseEntity.ok(bidService.submit(request));
    }

    @PutMapping("/{id}")
    public BidResponse updateByAdmin(@PathVariable Long id, @Valid @RequestBody BidAdminUpdateRequest request) {
        return bidService.updateByAdmin(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteByAdmin(@PathVariable Long id) {
        bidService.deleteByAdmin(id);
        return ResponseEntity.noContent().build();
    }
}