package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class InvalidIdentifierException extends AppException {

    public InvalidIdentifierException(String identifier) {
        super("Tên bảng hoặc cột không hợp lệ (nguy cơ SQL Injection): " + identifier, HttpStatus.BAD_REQUEST);
    }
}
