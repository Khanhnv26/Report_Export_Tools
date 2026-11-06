package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class TemplateProcessingException extends AppException {

    public TemplateProcessingException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    public TemplateProcessingException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
    }
}
