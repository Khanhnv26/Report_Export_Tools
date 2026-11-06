package com.report.tool.service.impl;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.BaseTable;
import com.report.tool.dto.request.ReportConfigRequest.JoinTable;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.exception.ReportExecutionException;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.SqlBuilder;
import com.report.tool.service.ReportEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportEngineServiceImpl implements ReportEngineService {

    private static final int BATCH_SIZE = 1000;
    private final DataSourceRegistry registry;
    private final SqlBuilder sqlBuilder;

    @Override
    public List<Map<String, Object>> executeReport(ReportConfigRequest request) {
        if (request == null || request.getBaseTable() == null) {
            throw new InvalidReportConfigException("Cấu hình báo cáo không hợp lệ: Chưa chọn Bảng gốc!");
        }

        BaseTable baseTable = request.getBaseTable();
        if (baseTable.getDbId() == null || baseTable.getDbId().isBlank()) {
            throw new InvalidReportConfigException("Chưa chọn Database cho Bảng gốc!");
        }
        if (baseTable.getTableName() == null || baseTable.getTableName().isBlank()) {
            throw new InvalidReportConfigException("Chưa chọn Tên bảng cho Bảng gốc!");
        }
        if (baseTable.getColumns() == null || baseTable.getColumns().isEmpty()) {
            throw new InvalidReportConfigException("Vui lòng tích chọn ít nhất 1 cột từ Bảng gốc!");
        }

        try {
            NamedParameterJdbcTemplate baseJdbc = registry.getJdbc(baseTable.getDbId());
            Set<String> baseCols = new LinkedHashSet<>(baseTable.getColumns());

            // Đảm bảo lấy cả cột sourceKey của các bảng join
            if (request.getJoins() != null) {
                for (JoinTable j : request.getJoins()) {
                    if (j.getSourceKey() != null && !j.getSourceKey().isBlank()) {
                        baseCols.add(j.getSourceKey());
                    }
                }
            }

            boolean hasFilter = baseTable.getFilterColumn() != null && !baseTable.getFilterColumn().isBlank()
                    && baseTable.getFromDate() != null && !baseTable.getFromDate().isBlank()
                    && baseTable.getToDate() != null && !baseTable.getToDate().isBlank();

            String baseSql = sqlBuilder.buildBaseQuery(baseTable.getTableName(), baseCols, baseTable.getFilterColumn(), hasFilter);
            MapSqlParameterSource params = new MapSqlParameterSource();
            if (hasFilter) {
                params.addValue("fromDate", baseTable.getFromDate());
                params.addValue("toDate", baseTable.getToDate());
            }

            log.info("Thực thi query Bảng gốc [{}]: {}", baseTable.getDbId(), baseSql);
            List<Map<String, Object>> rawBaseRows = baseJdbc.queryForList(baseSql, params);
            if (rawBaseRows.isEmpty()) {
                return Collections.emptyList();
            }

            // Chuẩn hóa tên cột dạng tableName.columnName và giữ cả tên ngắn
            List<Map<String, Object>> resultRows = new ArrayList<>(rawBaseRows.size());
            for (Map<String, Object> raw : rawBaseRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                raw.forEach((col, val) -> {
                    row.put(baseTable.getTableName() + "." + col, val);
                    row.put(col, val);
                });
                resultRows.add(row);
            }

            // Thực hiện Hash-Join với các bảng phụ
            if (request.getJoins() != null) {
                for (JoinTable join : request.getJoins()) {
                    if (join.getDbId() != null && join.getTableName() != null
                            && join.getSourceKey() != null && join.getTargetKey() != null) {
                        performHashJoin(resultRows, join);
                    }
                }
            }

            return resultRows;
        } catch (Exception e) {
            log.error("Lỗi khi thực thi tổng hợp báo cáo: {}", e.getMessage(), e);
            throw new ReportExecutionException("Lỗi trong quá trình truy vấn và ghép dữ liệu: " + e.getMessage(), e);
        }
    }

    private void performHashJoin(List<Map<String, Object>> baseRows, JoinTable joinTable) {
        List<Object> distinctKeys = baseRows.stream()
                .map(r -> r.get(joinTable.getSourceKey()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (distinctKeys.isEmpty()) return;

        NamedParameterJdbcTemplate joinJdbc = registry.getJdbc(joinTable.getDbId());
        String joinSql = sqlBuilder.buildJoinQuery(joinTable.getTableName(), joinTable.getColumns(), joinTable.getTargetKey());

        Map<String, Map<String, Object>> lookupMap = new HashMap<>();

        for (int i = 0; i < distinctKeys.size(); i += BATCH_SIZE) {
            List<Object> batch = distinctKeys.subList(i, Math.min(i + BATCH_SIZE, distinctKeys.size()));
            List<Map<String, Object>> targetRows = joinJdbc.queryForList(joinSql, Map.of("keys", batch));

            for (Map<String, Object> tr : targetRows) {
                Object keyObj = tr.get(joinTable.getTargetKey());
                if (keyObj != null) {
                    lookupMap.putIfAbsent(String.valueOf(keyObj), tr);
                }
            }
        }

        for (Map<String, Object> baseRow : baseRows) {
            Object sourceValue = baseRow.get(joinTable.getSourceKey());
            if (sourceValue == null) {
                continue;
            }
            Map<String, Object> matched = lookupMap.get(String.valueOf(sourceValue));
            if (matched != null) {
                matched.forEach((col, val) ->
                        baseRow.put(joinTable.getTableName() + "." + col, val));
            }
        }
    }
}
