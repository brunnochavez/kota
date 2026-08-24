package com.bruno.kota.exceptions;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateResourceException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRule(BusinessRuleException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(InactiveResourceException.class)
    public ResponseEntity<Map<String, Object>> handleInactive(InactiveResourceException ex) {
        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", HttpStatus.CONFLICT.value(),
                "error", HttpStatus.CONFLICT.getReasonPhrase(),
                "message", ex.getMessage(),
                "existingId", ex.getExistingId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }


    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<Map<String, Object>> handleTransactionSystem(TransactionSystemException ex) {
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof ConstraintViolationException)) {
            cause = cause.getCause();
        }
        if (cause instanceof ConstraintViolationException cve) {
            String message = cve.getConstraintViolations().stream()
                    .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                    .collect(Collectors.joining("; "));
            return buildResponse(HttpStatus.BAD_REQUEST, message);
        }
        // Aqui embaixo (causa desconhecida) é justamente o tipo de erro que a gente NÃO
        // quer mostrar pro usuário — pode ser mensagem de driver JDBC, nome de tabela,
        // caminho de arquivo, etc. Loga completo (com stack trace) pro log do servidor,
        // que é onde a gente de fato investiga; devolve só uma mensagem genérica pro
        // cliente. Mesma lógica do handleGeneric logo abaixo.
        log.error("Erro inesperado (TransactionSystemException sem causa mapeada)", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado. Tente novamente ou entre em contato com o suporte.");
    }

    // Pega QUALQUER exceção não tratada pelos handlers específicos acima — sem esse
    // catch-all, um erro inesperado (NullPointerException, erro de conexão com o banco,
    // etc.) voltaria pro cliente como HTML de erro padrão do Spring/Tomcat, com stack
    // trace completo exposto. Antes esse handler devolvia ex.getMessage() direto pro
    // cliente, o que também vaza detalhes internos (mensagem de driver JDBC, nome de
    // classe, caminho de arquivo) — não é informação que um usuário, muito menos um
    // possível atacante, deveria ver. Loga o erro completo no servidor (é ali que dá pra
    // investigar de verdade) e devolve só uma mensagem genérica pro cliente.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Erro inesperado não tratado", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado. Tente novamente ou entre em contato com o suporte.");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );
        return ResponseEntity.status(status).body(body);
    }
}