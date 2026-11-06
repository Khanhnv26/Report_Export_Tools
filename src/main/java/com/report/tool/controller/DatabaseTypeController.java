package com.report.tool.controller;

import com.report.tool.database.DatabaseAdapters;
import com.report.tool.database.DatabaseType;
import com.report.tool.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class DatabaseTypeController {
    private final DatabaseAdapters adapters;

    @GetMapping("/api/database-types")
    public ApiResponse<List<DatabaseType>> list() {
        return ApiResponse.success(adapters.types());
    }
}
