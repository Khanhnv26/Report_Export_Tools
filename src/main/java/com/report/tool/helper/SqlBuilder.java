package com.report.tool.helper;

import com.report.tool.database.DatabaseAdapters;
import com.report.tool.database.DatabaseAdapter;
import com.report.tool.database.DatabaseIdentifiers;
import com.report.tool.database.DatabaseRowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SqlBuilder {
    private final DatabaseAdapters adapters;

    public String validateIdentifier(String identifier) {
        return DatabaseIdentifiers.validate(identifier);
    }

    public String buildBaseQuery(String dbType, String schema, String table, Collection<String> columns,
                                 String filterCol, boolean hasDateFilter) {
        DatabaseAdapter adapter = adapters.get(dbType);
        String safeTable = tableReference(adapter, schema, table);
        String colList = columns.stream()
                .map(column -> adapter.quoteIdentifier(validateIdentifier(column)))
                .distinct()
                .collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(colList).append(" FROM ").append(safeTable);
        if (hasDateFilter && filterCol != null && !filterCol.isBlank()) {
            String safeFilter = adapter.quoteIdentifier(validateIdentifier(filterCol));
            sql.append(" WHERE ").append(safeFilter).append(" >= :fromDate AND ")
                    .append(safeFilter).append(" < :toDateExclusive");
        }
        return adapter.limitQuery(sql.toString());
    }

    public String buildJoinQuery(String dbType, String schema, String table, Collection<String> columns, String targetKey) {
        DatabaseAdapter adapter = adapters.get(dbType);
        String safeTable = tableReference(adapter, schema, table);
        String safeTargetKey = adapter.quoteIdentifier(validateIdentifier(targetKey));
        Set<String> allCols = new LinkedHashSet<>(columns);
        allCols.add(targetKey);
        String colList = allCols.stream()
                .map(column -> adapter.quoteIdentifier(validateIdentifier(column)))
                .collect(Collectors.joining(", "));
        return adapter.limitQuery("SELECT " + colList + " FROM " + safeTable + " WHERE " + safeTargetKey + " IN (:keys)");
    }

    public int keyBatchSize(String dbType) { return adapters.get(dbType).keyBatchSize(); }

    public DatabaseRowMapper rowMapper(String dbType) { return new DatabaseRowMapper(adapters.get(dbType)); }

    private String tableReference(DatabaseAdapter adapter, String schema, String table) {
        validateIdentifier(table);
        if (schema != null && !schema.isBlank()) validateIdentifier(schema);
        return adapter.tableReference(schema, table);
    }
}
