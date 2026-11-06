package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;

public final class OracleAdapter implements DatabaseAdapter {
    public DatabaseType type() {
        return new DatabaseType("ORACLE", "Oracle", 1521, "oracle.jdbc.OracleDriver", "Service name", true);
    }
    public String jdbcUrl(DBConnectionRequest request) {
        return "jdbc:oracle:thin:@//" + host(request) + ":" + effectivePort(request) + "/" + request.getDatabase();
    }
    public String quoteIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    public String limitQuery(String sql) { return "SELECT * FROM (" + sql + ") report_rows WHERE ROWNUM <= :rowLimit"; }
    public MetadataScope metadataScope(Connection connection, String schema) throws SQLException {
        String resolved = schema == null || schema.isBlank() ? connection.getSchema() : schema;
        if (resolved == null || resolved.isBlank()) resolved = connection.getMetaData().getUserName();
        return new MetadataScope(null, resolved);
    }
    public Object readValue(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value != null) {
            String name = value.getClass().getName();
            if (name.equals("oracle.sql.TIMESTAMP") || name.equals("oracle.sql.DATE")) value = result.getTimestamp(column);
            else if (name.startsWith("oracle.sql.TIMESTAMP")) value = result.getString(column);
        }
        return JdbcValues.normalize(value);
    }
}
