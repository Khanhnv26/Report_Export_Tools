package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import java.sql.Connection;
import java.sql.SQLException;

public final class PostgreSqlAdapter implements DatabaseAdapter {
    public DatabaseType type() {
        return new DatabaseType("POSTGRESQL", "PostgreSQL", 5432, "org.postgresql.Driver", "Database", true);
    }
    public String jdbcUrl(DBConnectionRequest request) {
        return "jdbc:postgresql://" + host(request) + ":" + effectivePort(request) + "/" + request.getDatabase();
    }
    public String quoteIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    public String limitQuery(String sql) { return sql + " LIMIT :rowLimit"; }
    public MetadataScope metadataScope(Connection connection, String schema) throws SQLException {
        return new MetadataScope(connection.getCatalog(), schema == null || schema.isBlank() ? connection.getSchema() : schema);
    }
}
