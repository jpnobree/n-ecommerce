package com.atelier.shared.error;

public class BusinessException extends RuntimeException {

    public final ErrorCode code;

    public BusinessException(ErrorCode code) {
        this(code, code.title);
    }

    public BusinessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }
}
