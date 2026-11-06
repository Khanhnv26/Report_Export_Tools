package com.report.tool.controller;

import com.report.tool.dto.response.ApiResponse;
import com.report.tool.dto.response.ExcelColumnResponse;
import com.report.tool.dto.response.TemplateUploadResponse;
import com.report.tool.service.ExcelTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final ExcelTemplateService templateService;

    @GetMapping("/{templateId}/columns")
    public ResponseEntity<ApiResponse<List<ExcelColumnResponse>>> headers(
            @PathVariable String templateId, @RequestParam(defaultValue = "0") int headerRow) {
        return ResponseEntity.ok(ApiResponse.success(templateService.readHeaders(templateId, headerRow)));
    }

    @GetMapping("/{templateId}/detect-header")
    public ResponseEntity<ApiResponse<Integer>> detectHeader(@PathVariable String templateId) {
        return ResponseEntity.ok(ApiResponse.success(templateService.detectHeaderRow(templateId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TemplateUploadResponse>> uploadTemplate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "headerRow", required = false) Integer headerRow) {

        String templateId = templateService.saveTemplate(file);
        int targetHeaderRow = (headerRow != null && headerRow >= 0)
                ? headerRow
                : templateService.detectHeaderRow(templateId);
        List<ExcelColumnResponse> columns = templateService.readHeaders(templateId, targetHeaderRow);

        TemplateUploadResponse response = TemplateUploadResponse.builder()
                .templateId(templateId)
                .columns(columns)
                .headerRow(targetHeaderRow)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Tải lên và đọc cấu trúc file Excel mẫu thành công!", response));
    }
}
