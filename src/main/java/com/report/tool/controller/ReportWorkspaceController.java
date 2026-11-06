package com.report.tool.controller;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.OutputColumn;
import com.report.tool.dto.response.ApiResponse;
import com.report.tool.service.ReportEngineService;
import com.report.tool.service.ReportJobService;
import com.report.tool.service.ReportOutputService;
import com.report.tool.service.ReportWorkbookService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportWorkspaceController {
    private final ReportEngineService engine;
    private final ReportOutputService output;
    private final ReportWorkbookService workbooks;
    private final ReportJobService jobs;

    public record Preview(List<OutputColumn> columns, List<List<String>> rows, List<String> warnings,
                          boolean sampled, int processedRows, long elapsedMillis) { }

    @PostMapping("/preview-details")
    public ApiResponse<Preview> preview(@RequestBody ReportConfigRequest request) {
        long start = System.nanoTime();
        boolean full = output.needsFullRead(request);
        List<Map<String, Object>> rows = full ? engine.executeReport(request) : engine.previewReport(request);
        ReportOutputService.PreparedReport prepared = output.prepare(request, rows);
        return ApiResponse.success(new Preview(prepared.columns(), workbooks.preview(request, prepared), prepared.warnings(),
                !full, prepared.rows().size(), (System.nanoTime() - start) / 1000000));
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReportJobService.Status> start(@RequestBody ReportConfigRequest request) {
        return ApiResponse.success(jobs.start(request));
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<ReportJobService.Status> status(@PathVariable String id) {
        return ApiResponse.success(jobs.status(id));
    }

    @DeleteMapping("/jobs/{id}")
    public ApiResponse<ReportJobService.Status> cancel(@PathVariable String id) {
        return ApiResponse.success(jobs.cancel(id));
    }

    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id, @RequestParam(defaultValue = "false") boolean acknowledged) throws IOException {
        Path file = jobs.download(id, acknowledged);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("Bao_cao_" + id.substring(0, 8) + ".xlsx").build().toString())
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }
}
