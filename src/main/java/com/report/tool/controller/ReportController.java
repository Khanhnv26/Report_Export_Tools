package com.report.tool.controller;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.response.ApiResponse;
import com.report.tool.service.ExcelTemplateService;
import com.report.tool.service.ReportEngineService;
import com.report.tool.helper.ReportValues;
import com.report.tool.exception.ReportBusyException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportEngineService reportEngineService;
    private final ExcelTemplateService excelTemplateService;
    private final Semaphore exportSlots = new Semaphore(2);

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> preview(@RequestBody ReportConfigRequest request) {
        List<Map<String, Object>> previewRows = reportEngineService.previewReport(request).stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>();
            row.forEach((key, value) -> safe.put(key, ReportValues.preservePrecision(value)));
            return safe;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success("Xem trước 20 dòng dữ liệu thành công!", previewRows));
    }

    @PostMapping("/export")
    public void export(@RequestBody ReportConfigRequest request, HttpServletResponse response) throws IOException {
        if (!exportSlots.tryAcquire()) throw new ReportBusyException();
        try {
            writeExport(request, response);
        } finally {
            exportSlots.release();
        }
    }

    private void writeExport(ReportConfigRequest request, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> data = reportEngineService.executeReport(request);

        Path exportFile = Files.createTempFile("report-export-", ".xlsx");
        try {
            try (OutputStream out = Files.newOutputStream(exportFile)) {
                excelTemplateService.fillTemplate(request.getTemplateId(), request.getHeaderRow(),
                        data, request.getMapping(), out);
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String fileName = URLEncoder.encode("Bao_Cao_Tong_Hop_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8);
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            response.setContentLengthLong(Files.size(exportFile));
            Files.copy(exportFile, response.getOutputStream());
        } finally {
            Files.deleteIfExists(exportFile);
        }
    }
}
