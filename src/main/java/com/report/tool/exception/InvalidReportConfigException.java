package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class InvalidReportConfigException extends AppException {

    public InvalidReportConfigException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
