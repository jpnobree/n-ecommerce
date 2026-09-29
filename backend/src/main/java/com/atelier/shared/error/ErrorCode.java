package com.atelier.shared.error;

import org.springframework.http.HttpStatus;

/** Códigos de erro estáveis da API; o frontend traduz pelo código, não pelo texto. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dados inválidos"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado"),
    CONFLICT(HttpStatus.CONFLICT, "Conflito com o estado atual"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "O recurso foi alterado por outra operação"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Tente novamente mais tarde"),
    CSRF_CHECK_FAILED(HttpStatus.FORBIDDEN, "Requisição recusada"),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Conta bloqueada temporariamente por tentativas inválidas"),
    ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN, "Conta bloqueada. Fale com o atendimento"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "E-mail já cadastrado"),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Sessão expirada"),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "Sessão encerrada por segurança"),
    TOKEN_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "Link inválido ou expirado"),
    WRONG_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "Senha atual incorreta"),
    CPF_IN_USE(HttpStatus.CONFLICT, "CPF já cadastrado em outra conta"),
    ADDRESS_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Limite de endereços atingido");

    public final HttpStatus status;
    public final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }
}
