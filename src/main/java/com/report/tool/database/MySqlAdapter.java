package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import java.sql.Connection;
import java.sql.SQLException;

public final class MySqlAdapter implements DatabaseAdapter {
    public DatabaseType type() {
        return new DatabaseType("MYSQL", "MySQL", 3306, "com.mysql.cj.jdbc.Driver", "Database", false);
    }
    public String jdbcUrl(DBConnectionRequest request) {
        return "jdbc:mysql://" + host(request) + ":" + effectivePort(request) + "/" + request.getDatabase()
                + "?useSSL=false&allowPublicKeyRetrieval=true";
    }
    public String quoteIdentifier(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
    public String limitQuery(String sql) { return sql + " LIMIT :rowLimit"; }
    public MetadataScope metadataScope(Connection connection, String schema) throws SQLException {
        return new MetadataScope(connection.getCatalog(), null);
    }
}
