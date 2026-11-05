package com.report.tool.helper;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SqlBuilder {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");

    public String validateIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Tên bảng hoặc cột không hợp lệ: " + identifier);
        }
        return identifier;
    }

    public String buildBaseQuery(String table, Collection<String> columns, String filterCol, boolean hasDateFilter) {
        String safeTable = validateIdentifier(table);
        String colList = columns.stream()
                .map(this::validateIdentifier)
                .distinct()
                .collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(colList).append(" FROM ").append(safeTable);
        if (hasDateFilter && filterCol != null && !filterCol.isBlank()) {
            sql.append(" WHERE ").append(validateIdentifier(filterCol)).append(" BETWEEN :fromDate AND :toDate");
        }
        return sql.toString();
    }

    public String buildJoinQuery(String table, Collection<String> columns, String targetKey) {
        String safeTable = validateIdentifier(table);
        String safeTargetKey = validateIdentifier(targetKey);
        Set<String> allCols = new LinkedHashSet<>(columns);
        allCols.add(safeTargetKey);
        String colList = allCols.stream()
                .map(this::validateIdentifier)
                .collect(Collectors.joining(", "));
        return "SELECT " + colList + " FROM " + safeTable + " WHERE " + safeTargetKey + " IN (:keys)";
    }




}
