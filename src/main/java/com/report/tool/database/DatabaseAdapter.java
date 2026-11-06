package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;

/** All database-specific decisions used by connection, metadata and reporting callers. */
public interface DatabaseAdapter {
    DatabaseType type();
    String jdbcUrl(DBConnectionRequest request);
    String quoteIdentifier(String identifier);
    String limitQuery(String sql);
    MetadataScope metadataScope(Connection connection, String schema) throws SQLException;

    default int effectivePort(DBConnectionRequest request) {
        return request.getPort() == 0 ? type().defaultPort() : request.getPort();
    }

    default String tableReference(String schema, String table) {
        return schema == null || schema.isBlank() ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    default int keyBatchSize() { return 1000; }

    default Object readValue(ResultSet result, int column) throws SQLException {
        return JdbcValues.normalize(result.getObject(column));
    }

    default String host(DBConnectionRequest request) {
        String host = request.getHost();
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
