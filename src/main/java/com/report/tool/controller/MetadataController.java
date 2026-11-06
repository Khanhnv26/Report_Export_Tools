package com.report.tool.controller;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.dto.response.ApiResponse;
import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;
import com.report.tool.service.MetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/databases")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadataService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DatabaseResponse>>> listDatabases() {
        List<DatabaseResponse> databases = metadataService.getAllDataBases();
        return ResponseEntity.ok(ApiResponse.success(databases));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DatabaseResponse>> connectDatabase(@RequestBody DBConnectionRequest request) {
        DatabaseResponse response = metadataService.addDataBase(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Kết nối Database thành công!", response));
    }

    @DeleteMapping("/{databaseId}")
    public ResponseEntity<ApiResponse<Void>> deleteDatabase(@PathVariable("databaseId") String databaseId) {
        metadataService.removeDataBase(databaseId);
        return ResponseEntity.ok(ApiResponse.success("Đã xóa kết nối Database thành công!", null));
    }

    @GetMapping("/{databaseId}/tables")
    public ResponseEntity<ApiResponse<List<String>>> getTables(@PathVariable("databaseId") String databaseId) {
        List<String> tables = metadataService.getTables(databaseId);
        return ResponseEntity.ok(ApiResponse.success(tables));
    }

    @GetMapping("/{databaseId}/tables/{tableName}/columns")
    public ResponseEntity<ApiResponse<List<ColumnResponse>>> getColumns(@PathVariable("databaseId") String databaseId,
                                                                        @PathVariable("tableName") String tableName) {
        List<ColumnResponse> columns = metadataService.getColumns(databaseId, tableName);
        return ResponseEntity.ok(ApiResponse.success(columns));
    }
}
