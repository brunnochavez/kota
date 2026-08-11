package com.bruno.kota.exceptions;

public class InactiveResourceException extends RuntimeException {

    private final Long existingId;

    public InactiveResourceException(String message, Long existingId) {
        super(message);
        this.existingId = existingId;
    }

    public Long getExistingId() {
        return existingId;
    }
}