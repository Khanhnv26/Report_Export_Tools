package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class DatabaseConnectionException extends AppException {

    public DatabaseConnectionException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    public DatabaseConnectionException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
    }
}
