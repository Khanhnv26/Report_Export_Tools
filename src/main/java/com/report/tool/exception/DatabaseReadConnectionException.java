package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class DatabaseReadConnectionException extends AppException {
    public DatabaseReadConnectionException(String source, Throwable cause) {
        super("Mất kết nối tới nguồn “" + source + "”. Tác vụ đã dùng hết một lần thử lại. Kiểm tra kết nối nguồn và chạy lại báo cáo.",
                HttpStatus.SERVICE_UNAVAILABLE, cause);
    }
}
