package com.bruno.kota.dtos;

import java.util.List;

public record QuotationImportResult(
        boolean needsMapping,
        List<String> headersFound,
        QuotationResponse quotation
) {}