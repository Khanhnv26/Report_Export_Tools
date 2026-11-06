package com.report.tool.service.impl;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.BaseTable;
import com.report.tool.dto.request.ReportConfigRequest.JoinTable;
import com.report.tool.exception.AppException;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.exception.ReportExecutionException;
import com.report.tool.exception.ReportBusyException;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.SqlBuilder;
import com.report.tool.helper.ReportQueries;
import com.report.tool.service.ReportCancellation;
import com.report.tool.service.ReportEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportEngineServiceImpl implements ReportEngineService {
    private static final int PREVIEW_SIZE = 20;
    private final DataSourceRegistry registry;
    private final SqlBuilder sqlBuilder;
    private final Semaphore reportSlots = new Semaphore(2);

    @Value("${report.max-rows:50000}")
    private int maxRows = 50000;

    @Override
    public List<Map<String, Object>> executeReport(ReportConfigRequest request) {
        return withPermit(request, false);
    }

    @Override
    public List<Map<String, Object>> previewReport(ReportConfigRequest request) {
        return withPermit(request, true);
    }

    private List<Map<String, Object>> withPermit(ReportConfigRequest request, boolean preview) {
        if (!reportSlots.tryAcquire()) throw new ReportBusyException();
        try { return execute(request, preview); }
        finally { reportSlots.release(); }
    }

    private List<Map<String, Object>> execute(ReportConfigRequest request, boolean preview) {
        validate(request);
        BaseTable base = request.getBaseTable();
        List<JoinTable> joins = request.getJoins() == null ? List.of() : request.getJoins();
        String baseAlias = alias(base.getAlias(), base.getTableName());
        Map<String, Set<String>> sourceColumns = collectColumns(base, joins, baseAlias);
        MapSqlParameterSource params = dateParameters(base);
        int limit = preview ? PREVIEW_SIZE : maxRows;
        params.addValue("rowLimit", preview ? PREVIEW_SIZE : maxRows + 1);
        String sql = sqlBuilder.buildBaseQuery(registry.getDatabaseType(base.getDbId()), registry.getDatabaseSchema(base.getDbId()),
                base.getTableName(), sourceColumns.get(baseAlias), base.getFilterColumn(), params.hasValue("fromDate"));
        try {
            NamedParameterJdbcTemplate jdbc = registry.getJdbc(base.getDbId());
            List<Map<String, Object>> rawRows = ReportQueries.query(jdbc, sql, params, sqlBuilder.rowMapper(registry.getDatabaseType(base.getDbId())));
            checkLimit(rawRows.size());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> raw : rawRows) {
                ReportCancellation.check();
                Map<String, Object> row = new LinkedHashMap<>();
                raw.forEach((column, value) -> {
                    row.put(baseAlias + "." + column, value);
                    row.put(column, value); // Unqualified keys refer to the base table for old profiles.
                });
                rows.add(row);
            }
            for (JoinTable join : joins) {
                rows = join(rows, join, sourceColumns, limit, preview);
            }
            return rows;
        } catch (CancellationException e) {
            throw e;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Report query failed", e);
            throw new ReportExecutionException("Không thể tổng hợp báo cáo. Kiểm tra cấu hình và kết nối database.", e);
        }
    }

    private void validate(ReportConfigRequest request) {
        if (request == null || request.getBaseTable() == null) {
            throw new InvalidReportConfigException("Chưa chọn bảng gốc!");
        }
        if (maxRows < PREVIEW_SIZE || maxRows > 1000000) {
            throw new IllegalStateException("report.max-rows phải nằm trong khoảng 20 đến 1000000.");
        }
        BaseTable base = request.getBaseTable();
        require(base.getDbId(), "Chưa chọn database gốc!");
        sqlBuilder.validateIdentifier(base.getTableName());
        if (base.getColumns() == null || base.getColumns().isEmpty()) {
            throw new InvalidReportConfigException("Chọn ít nhất một cột bảng gốc!");
        }
        Set<String> aliases = new HashSet<>();
        aliases.add(sqlBuilder.validateIdentifier(alias(base.getAlias(), base.getTableName())));
        if (request.getJoins() != null) {
            for (JoinTable join : request.getJoins()) {
                if (join == null) throw new InvalidReportConfigException("Bảng ghép không hợp lệ!");
                require(join.getDbId(), "Chưa chọn database phụ!");
                sqlBuilder.validateIdentifier(join.getTableName());
                sqlBuilder.validateIdentifier(join.getTargetKey());
                require(join.getSourceKey(), "Chưa chọn khóa nguồn!");
                String name = sqlBuilder.validateIdentifier(alias(join.getAlias(), join.getTableName()));
                if (!aliases.add(name)) {
                    throw new InvalidReportConfigException("Tên nguồn bị trùng: " + name + ". Hãy đặt alias khác nhau.");
                }
                if (join.getColumns() == null) throw new InvalidReportConfigException("Danh sách cột phụ không hợp lệ!");
            }
        }
    }

    // Include hidden keys from their actual source, including keys needed by later joins.
    private Map<String, Set<String>> collectColumns(BaseTable base, List<JoinTable> joins, String baseAlias) {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        columns.put(baseAlias, new LinkedHashSet<>(base.getColumns()));
        for (JoinTable join : joins) {
            String[] key = join.getSourceKey().split("\\.", -1);
            String source = key.length == 1 ? baseAlias : key[0];
            String column = key[key.length - 1];
            if (key.length > 2 || !columns.containsKey(source)) {
                throw new InvalidReportConfigException("Khóa nguồn phải thuộc bảng gốc hoặc bảng đã ghép: " + join.getSourceKey());
            }
            sqlBuilder.validateIdentifier(column);
            columns.get(source).add(column);
            Set<String> selected = new LinkedHashSet<>(join.getColumns());
            selected.add(join.getTargetKey());
            columns.put(alias(join.getAlias(), join.getTableName()), selected);
        }
        columns.values().forEach(cols -> cols.forEach(sqlBuilder::validateIdentifier));
        return columns;
    }

    private MapSqlParameterSource dateParameters(BaseTable base) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        boolean column = present(base.getFilterColumn());
        boolean from = present(base.getFromDate());
        boolean to = present(base.getToDate());
        if (!column && !from && !to) return params;
        if (!column || !from || !to) {
            throw new InvalidReportConfigException("Bộ lọc cần đủ cột ngày, ngày bắt đầu và ngày kết thúc.");
        }
        try {
            LocalDate start = LocalDate.parse(base.getFromDate());
            LocalDate end = LocalDate.parse(base.getToDate());
            if (end.isBefore(start)) throw new InvalidReportConfigException("Ngày kết thúc phải từ ngày bắt đầu trở đi.");
            params.addValue("fromDate", Date.valueOf(start));
            params.addValue("toDateExclusive", Date.valueOf(end.plusDays(1)));
            return params;
        } catch (DateTimeException | ArithmeticException e) {
            throw new InvalidReportConfigException("Ngày phải có định dạng yyyy-MM-dd hợp lệ.");
        }
    }

    private List<Map<String, Object>> join(List<Map<String, Object>> rows, JoinTable join,
                                           Map<String, Set<String>> columns, int limit, boolean preview) {
        List<Object> keys = rows.stream().map(row -> row.get(join.getSourceKey()))
                .filter(Objects::nonNull).distinct().toList();
        String name = alias(join.getAlias(), join.getTableName());
        if (keys.isEmpty()) {
            rows.forEach(row -> columns.get(name).forEach(column -> row.put(name + "." + column, null)));
            return rows;
        }
        String dbType = registry.getDatabaseType(join.getDbId());
        String sql = sqlBuilder.buildJoinQuery(dbType, registry.getDatabaseSchema(join.getDbId()),
                join.getTableName(), columns.get(name), join.getTargetKey());
        int batchSize = sqlBuilder.keyBatchSize(dbType);
        Map<Object, List<Map<String, Object>>> lookup = new HashMap<>();
        NamedParameterJdbcTemplate jdbc = registry.getJdbc(join.getDbId());
        int fetched = 0;
        for (int i = 0; i < keys.size(); i += batchSize) {
            List<Object> batch = keys.subList(i, Math.min(i + batchSize, keys.size()));
            ReportCancellation.check();
            List<Map<String, Object>> targetRows = ReportQueries.query(jdbc, sql,
                    new MapSqlParameterSource().addValue("keys", batch).addValue("rowLimit", maxRows - fetched + 1), sqlBuilder.rowMapper(dbType));
            fetched += targetRows.size();
            checkLimit(fetched);
            for (Map<String, Object> target : targetRows) {
                Object key = target.get(join.getTargetKey());
                if (key != null) lookup.computeIfAbsent(normalizeKey(key), ignored -> new ArrayList<>()).add(target);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ReportCancellation.check();
            Object key = row.get(join.getSourceKey());
            List<Map<String, Object>> matches = key == null ? null : lookup.get(normalizeKey(key));
            if (matches == null) matches = Collections.singletonList(Collections.emptyMap());
            for (Map<String, Object> match : matches) {
                Map<String, Object> merged = new LinkedHashMap<>(row);
                columns.get(name).forEach(column -> merged.put(name + "." + column, match.get(column)));
                result.add(merged);
                if (preview && result.size() == limit) return result;
                checkLimit(result.size());
            }
        }
        return result;
    }

    private Object normalizeKey(Object key) {
        if (key instanceof Number) return new BigDecimal(key.toString()).stripTrailingZeros().toPlainString();
        return key.toString();
    }

    private void checkLimit(int count) {
        if (count > maxRows) {
            throw new InvalidReportConfigException("Báo cáo vượt giới hạn " + maxRows
                    + " dòng. Hãy thu hẹp bộ lọc; dữ liệu không bị cắt để xuất thiếu.");
        }
    }

    private String alias(String alias, String table) { return present(alias) ? alias : table; }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    private void require(String value, String message) {
        if (!present(value)) throw new InvalidReportConfigException(message);
    }
}
