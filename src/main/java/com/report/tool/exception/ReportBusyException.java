package com.report.tool.exception;

import org.springframework.http.HttpStatus;

public class ReportBusyException extends AppException {
    public ReportBusyException() {
        super("Đang có nhiều báo cáo được xử lý. Vui lòng thử lại sau.", HttpStatus.TOO_MANY_REQUESTS);
    }
}
