package com.report.tool.service;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.OutputColumn;
import com.report.tool.dto.request.ReportConfigRequest.Filter;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.helper.ReportValues;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportOutputService {
    private final ExcelTemplateService templates;
    public record PreparedReport(List<OutputColumn> columns, List<List<Object>> rows, List<String> warnings, boolean template) { }
    private static final Set<String> TYPES = Set.of("AUTO", "TEXT", "NUMBER", "DATE", "DATETIME", "BOOLEAN");
    private static final Set<String> FORMATS = Set.of("GENERAL", "INTEGER", "DECIMAL", "MONEY", "DATE", "DATETIME");
    private static final Set<String> OPERATORS = Set.of("EQ", "NE", "CONTAINS", "GT", "GTE", "LT", "LTE", "EMPTY", "NOT_EMPTY");

    public boolean isTemplate(ReportConfigRequest config) {
        if (config.getOutputMode() == null) return config.getTemplateId() != null && !config.getTemplateId().isBlank();
        if (!Set.of("TABLE", "TEMPLATE").contains(config.getOutputMode())) fail("Chế độ xuất không hợp lệ.");
        return "TEMPLATE".equals(config.getOutputMode());
    }

    public boolean needsFullRead(ReportConfigRequest config) {
        return config.getFilters() != null && !config.getFilters().isEmpty() || present(config.getSortField());
    }

    public PreparedReport prepare(ReportConfigRequest config, List<Map<String, Object>> source) {
        boolean template = isTemplate(config);
        Set<String> fields = selectedFields(config);
        List<OutputColumn> columns = columns(config, template);
        if (columns.isEmpty() || columns.size() > 200) fail("Chọn từ 1 đến 200 cột đầu ra.");
        Set<String> targets = new HashSet<>();
        for (OutputColumn column : columns) {
            if (column == null || !fields.contains(column.getField())) fail("Cột đầu ra tham chiếu trường nguồn không tồn tại.");
            if (!present(column.getTitle()) || column.getTitle().length() > 200) fail("Tiêu đề cột phải có từ 1 đến 200 ký tự.");
            if (column.getType() == null || column.getFormat() == null || !TYPES.contains(column.getType()) || !FORMATS.contains(column.getFormat())) fail("Kiểu hoặc định dạng cột không hợp lệ.");
            if (template && (column.getLetter() == null || !column.getLetter().matches("[A-Z]{1,3}")
                    || CellReference.convertColStringToIndex(column.getLetter()) >= 16384
                    || !targets.add(column.getLetter()))) fail("Cột đích trong template không hợp lệ hoặc bị trùng.");
        }
        List<Filter> filters = config.getFilters() == null ? List.of() : config.getFilters();
        if (filters.size() > 20) fail("Tối đa 20 điều kiện lọc.");
        for (Filter filter : filters) {
            if (filter == null || !fields.contains(filter.getField()) || filter.getOperator() == null || !OPERATORS.contains(filter.getOperator())) fail("Điều kiện lọc không hợp lệ.");
            if (!Set.of("EMPTY", "NOT_EMPTY").contains(filter.getOperator()) && filter.getValue() == null) fail("Nhập giá trị lọc.");
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : source) {
            ReportCancellation.check();
            if (filters.stream().allMatch(filter -> matches(row.get(filter.getField()), filter))) filtered.add(row);
        }
        if (present(config.getSortField())) {
            if (!fields.contains(config.getSortField()) || config.getSortDirection() == null || !Set.of("ASC", "DESC").contains(config.getSortDirection())) fail("Cấu hình sắp xếp không hợp lệ.");
            Comparator<Map<String, Object>> comparator = (a, b) -> compare(a.get(config.getSortField()), b.get(config.getSortField()));
            if ("DESC".equals(config.getSortDirection())) comparator = comparator.reversed();
            filtered.sort(comparator);
        }
        List<String> warnings = new ArrayList<>();
        if (config.getJoins() != null) for (ReportConfigRequest.JoinTable join : config.getJoins()) {
            String name = present(join.getAlias()) ? join.getAlias() : join.getTableName();
            long unmatched = filtered.stream().filter(row -> row.get(name + "." + join.getTargetKey()) == null).count();
            if (unmatched > 0) warnings.add(unmatched + " dòng kết quả không có dữ liệu từ nguồn “" + name + "”. Dòng gốc vẫn được giữ.");
        }
        List<List<Object>> rows = new ArrayList<>(filtered.size());
        int rowNumber = 0;
        for (Map<String, Object> sourceRow : filtered) {
            ReportCancellation.check();
            rowNumber++;
            List<Object> row = new ArrayList<>(columns.size());
            for (OutputColumn column : columns) {
                try { row.add(convert(sourceRow.get(column.getField()), column.getType())); }
                catch (RuntimeException ex) { fail("Dòng " + rowNumber + ", cột “" + column.getTitle()
                        + "”: không chuyển được sang kiểu " + column.getType() + ". Kiểm tra dữ liệu hoặc chọn kiểu Chữ/Tự động."); }
            }
            rows.add(row);
        }
        if (rows.isEmpty()) warnings.add("Không có dòng dữ liệu phù hợp. File chỉ có tiêu đề hoặc bố cục template.");
        return new PreparedReport(columns, rows, warnings, template);
    }

    private List<OutputColumn> columns(ReportConfigRequest config, boolean template) {
        if (config.getOutputColumns() != null && !config.getOutputColumns().isEmpty()) {
            if (template) {
                Map<String, String> headers = new HashMap<>();
                templates.readHeaders(config.getTemplateId(), config.getHeaderRow()).forEach(header -> headers.put(header.getLetter(), header.getTitle()));
                for (OutputColumn col : config.getOutputColumns()) {
                    if (col == null || !headers.containsKey(col.getLetter())) fail("Cột đích không có trong template.");
                    col.setTitle(headers.get(col.getLetter()));
                }
            }
            List<OutputColumn> selected = new ArrayList<>(config.getOutputColumns());
            if (template) selected.sort(Comparator.comparingInt(column -> CellReference.convertColStringToIndex(column.getLetter())));
            return selected;
        }
        if (!template) return List.of();
        Map<String, String> titles = new LinkedHashMap<>();
        templates.readHeaders(config.getTemplateId(), config.getHeaderRow()).forEach(header -> titles.put(header.getLetter(), header.getTitle()));
        List<OutputColumn> result = new ArrayList<>();
        if (config.getMapping() != null) config.getMapping().forEach((letter, field) -> {
            if (present(field)) {
                if (!titles.containsKey(letter)) fail("Cột đích không có trong template.");
                result.add(OutputColumn.builder().letter(letter).field(field).title(titles.get(letter)).build());
            }
        });
        return result;
    }

    private Set<String> selectedFields(ReportConfigRequest config) {
        if (config.getBaseTable() == null) fail("Chưa chọn nguồn dữ liệu.");
        Set<String> fields = new HashSet<>();
        ReportConfigRequest.BaseTable base = config.getBaseTable();
        String name = present(base.getAlias()) ? base.getAlias() : base.getTableName();
        if (base.getColumns() != null) base.getColumns().forEach(col -> { fields.add(name + "." + col); fields.add(col); });
        if (config.getJoins() != null) for (ReportConfigRequest.JoinTable join : config.getJoins()) {
            String alias = present(join.getAlias()) ? join.getAlias() : join.getTableName();
            if (join.getColumns() != null) join.getColumns().forEach(col -> fields.add(alias + "." + col));
        }
        return fields;
    }

    private boolean matches(Object actual, Filter filter) {
        boolean empty = actual == null || actual.toString().isBlank();
        return switch (filter.getOperator()) {
            case "EMPTY" -> empty;
            case "NOT_EMPTY" -> !empty;
            case "CONTAINS" -> !empty && actual.toString().toLowerCase(Locale.ROOT).contains(filter.getValue().toLowerCase(Locale.ROOT));
            case "EQ" -> !empty && compare(actual, filter.getValue()) == 0;
            case "NE" -> !empty && compare(actual, filter.getValue()) != 0;
            case "GT" -> !empty && compare(actual, filter.getValue()) > 0;
            case "GTE" -> !empty && compare(actual, filter.getValue()) >= 0;
            case "LT" -> !empty && compare(actual, filter.getValue()) < 0;
            case "LTE" -> !empty && compare(actual, filter.getValue()) <= 0;
            default -> false;
        };
    }

    private int compare(Object left, Object right) {
        ReportCancellation.check();
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        if (left instanceof Number) {
            try { return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())); }
            catch (NumberFormatException ex) { fail("Giá trị lọc của cột số phải là số hợp lệ."); }
        }
        return left.toString().compareToIgnoreCase(right.toString());
    }

    private Object convert(Object value, String type) {
        if (value == null) return null;
        if (type.equals("AUTO")) return ReportValues.preservePrecision(value);
        if (type.equals("TEXT")) return value.toString();
        if (value.toString().isBlank()) return null;
        return switch (type) {
            case "NUMBER" -> {
                BigDecimal number = new BigDecimal(value.toString().strip());
                if (ReportValues.preservePrecision(number) instanceof String) throw new IllegalArgumentException();
                yield number;
            }
            case "BOOLEAN" -> {
                String text = value.toString().toLowerCase(Locale.ROOT);
                if (!Set.of("true", "false", "1", "0").contains(text)) throw new IllegalArgumentException();
                yield text.equals("true") || text.equals("1");
            }
            case "DATE" -> dateTime(value).toLocalDate();
            case "DATETIME" -> dateTime(value);
            default -> throw new IllegalArgumentException();
        };
    }

    private LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof LocalDate date) return date.atStartOfDay();
        if (value instanceof Date date) {
            if ("java.sql.Date".equals(date.getClass().getName())) {
                return LocalDate.parse(date.toString()).atStartOfDay();
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
        }
        String text = value.toString().strip().replace(' ', 'T');
        try { return LocalDateTime.parse(text); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text).toLocalDateTime(); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(text).atStartOfDay(); } catch (DateTimeParseException ignored) { }
        return LocalDate.parse(text, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)).atStartOfDay();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void fail(String message) { throw new InvalidReportConfigException(message); }
}
