package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class DatabaseNotFoundException extends AppException {

    public DatabaseNotFoundException(String dbId) {
        super("Không tìm thấy kết nối Database với mã: " + dbId, HttpStatus.NOT_FOUND);
    }
}
