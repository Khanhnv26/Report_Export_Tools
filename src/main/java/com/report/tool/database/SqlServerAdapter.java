package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import java.sql.Connection;
import java.sql.SQLException;

public final class SqlServerAdapter implements DatabaseAdapter {
    public DatabaseType type() {
        return new DatabaseType("SQLSERVER", "SQL Server (MSSQL)", 1433,
                "com.microsoft.sqlserver.jdbc.SQLServerDriver", "Database", true);
    }
    public String jdbcUrl(DBConnectionRequest request) {
        return "jdbc:sqlserver://" + host(request) + ":" + effectivePort(request) + ";databaseName="
                + request.getDatabase() + ";encrypt=true;trustServerCertificate=true;";
    }
    public String quoteIdentifier(String identifier) { return "[" + identifier.replace("]", "]]") + "]"; }
    public String limitQuery(String sql) { return "SELECT TOP (:rowLimit) " + sql.substring("SELECT ".length()); }
    public MetadataScope metadataScope(Connection connection, String schema) throws SQLException {
        String resolved = schema == null || schema.isBlank() ? connection.getSchema() : schema;
        return new MetadataScope(connection.getCatalog(), resolved == null ? "dbo" : resolved);
    }
}
