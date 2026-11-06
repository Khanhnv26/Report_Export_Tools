package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class ReportExecutionException extends AppException {

    public ReportExecutionException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public ReportExecutionException(String message, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
