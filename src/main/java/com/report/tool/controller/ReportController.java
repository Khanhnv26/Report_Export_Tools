package com.report.tool.controller;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.response.ApiResponse;
import com.report.tool.service.ExcelTemplateService;
import com.report.tool.service.ReportEngineService;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportEngineService reportEngineService;
    private final ExcelTemplateService excelTemplateService;

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> preview(@RequestBody ReportConfigRequest request) {
        List<Map<String, Object>> allRows = reportEngineService.executeReport(request);
        List<Map<String, Object>> previewRows = allRows.stream().limit(20).toList();
        return ResponseEntity.ok(ApiResponse.success("Xem trước 20 dòng dữ liệu thành công!", previewRows));
    }

    @PostMapping("/export")
    public void export(@RequestBody ReportConfigRequest request, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> data = reportEngineService.executeReport(request);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("Bao_Cao_Tong_Hop_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

        try (OutputStream out = response.getOutputStream()) {
            excelTemplateService.fillTemplate(
                    request.getTemplateId(),
                    request.getHeaderRow(),
                    data,
                    request.getMapping(),
                    out
            );
            out.flush();
        }
    }
}
