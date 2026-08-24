package com.bruno.kota.controllers;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bruno.kota.dtos.ImportProfileResponse;
import com.bruno.kota.services.ImportProfileService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/import-profiles")
@RequiredArgsConstructor
public class ImportProfileController {

    private final ImportProfileService importProfileService;

    @GetMapping("/current")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportProfileResponse findCurrent() {
        return importProfileService.findCurrent();
    }
}