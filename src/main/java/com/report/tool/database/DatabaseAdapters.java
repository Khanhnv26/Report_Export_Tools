package com.report.tool.database;

import com.report.tool.exception.DatabaseConnectionException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;

@Component
public class DatabaseAdapters {
    private final List<DatabaseAdapter> adapters = List.of(new MySqlAdapter(), new PostgreSqlAdapter(),
            new SqlServerAdapter(), new OracleAdapter());

    public DatabaseAdapter get(String type) {
        String normalized = type == null || type.isBlank() ? "POSTGRESQL" : type.toUpperCase(Locale.ROOT);
        if (normalized.equals("MSSQL")) normalized = "SQLSERVER";
        for (DatabaseAdapter adapter : adapters) {
            if (adapter.type().id().equals(normalized)) return adapter;
        }
        throw new DatabaseConnectionException("Chưa hỗ trợ loại database: " + type);
    }

    public List<DatabaseType> types() { return adapters.stream().map(DatabaseAdapter::type).toList(); }
}
