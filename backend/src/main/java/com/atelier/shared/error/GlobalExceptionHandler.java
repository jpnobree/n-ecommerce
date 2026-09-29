package com.atelier.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/** Todos os erros saem como RFC 9457 Problem Details com "code" e "requestId" (PRD, seção 17.1). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> business(BusinessException ex) {
        return problem(ex.code, ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimisticLock() {
        return problem(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.title);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade: {}", ex.getMostSpecificCause().getMessage());
        return problem(ErrorCode.CONFLICT, ErrorCode.CONFLICT.title);
    }

    // Sem isso, negações do @PreAuthorize cairiam no handler genérico como 500.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied() {
        var body = ProblemDetail.forStatus(403);
        body.setTitle("Acesso negado");
        body.setProperty("code", "FORBIDDEN");
        body.setProperty("requestId", MDC.get("requestId"));
        return ResponseEntity.status(403).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        var body = ProblemDetail.forStatus(500);
        body.setTitle("Erro interno");
        body.setProperty("code", "INTERNAL_ERROR");
        body.setProperty("requestId", MDC.get("requestId"));
        return ResponseEntity.status(500).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, "Verifique os campos informados.");
        body.setTitle(ErrorCode.VALIDATION_ERROR.title);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Ponto único por onde passam também os erros do Spring MVC (400, 404, 405, 415...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                pd.setProperty("code", status.value() == 400 ? ErrorCode.VALIDATION_ERROR.name() : "HTTP_" + status.value());
            }
            pd.setProperty("requestId", MDC.get("requestId"));
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        return ResponseEntity.status(code.status).body(problemBody(code, detail));
    }

    public static ProblemDetail problemBody(ErrorCode code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(code.status, detail);
        body.setTitle(code.title);
        body.setProperty("code", code.name());
        body.setProperty("requestId", MDC.get("requestId"));
        return body;
    }
}
