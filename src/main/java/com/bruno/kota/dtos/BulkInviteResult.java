package com.bruno.kota.dtos;

import java.util.List;

public record BulkInviteResult(
        int invited,
        List<String> failedNames
) {}
