package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class TemplateNotFoundException extends AppException {

    public TemplateNotFoundException(String templateId) {
        super("Không tìm thấy file Excel mẫu: " + templateId, HttpStatus.NOT_FOUND);
    }
}
