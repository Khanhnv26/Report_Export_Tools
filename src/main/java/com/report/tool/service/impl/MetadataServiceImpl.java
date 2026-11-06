package com.report.tool.service.impl;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;
import com.report.tool.database.DatabaseAdapters;
import com.report.tool.database.DatabaseIdentifiers;
import com.report.tool.database.MetadataScope;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.DatabaseReadRetry;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import com.report.tool.service.MetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataServiceImpl implements MetadataService {

    private final DataSourceRegistry dataSourceRegistry;
    private final DatabaseAdapters adapters;

    @Override
    public DatabaseResponse addDataBase(DBConnectionRequest request) {
        DBConnectionRequest saved = dataSourceRegistry.connectAndRegister(request);
        return DatabaseResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .databaseName(saved.getDatabase())
                .schema(saved.getSchema())
                .dbType(saved.getDbType())
                .host(saved.getHost())
                .port(saved.getPort())
                .username(saved.getUsername())
                .connected(dataSourceRegistry.isConnected(saved.getId()))
                .build();
    }

    @Override
    public void removeDataBase(String databaseId) {
        dataSourceRegistry.removeDatabase(databaseId);
    }

    @Override
    public List<DatabaseResponse> getAllDataBases() {
        return dataSourceRegistry.getAllDatabases().stream()
                .map(db -> DatabaseResponse.builder()
                        .id(db.getId())
                        .name(db.getName())
                        .databaseName(db.getDatabase())
                        .schema(db.getSchema())
                        .dbType(db.getDbType())
                        .host(db.getHost())
                        .port(db.getPort())
                        .username(db.getUsername())
                        .connected(dataSourceRegistry.isConnected(db.getId()))
                        .build())
                .toList();
    }

    @Override
    public List<String> getTables(String dbId) {
        return readMetadata(dbId, conn -> {
            List<String> tables = new ArrayList<>();
            DatabaseMetaData meta = conn.getMetaData();
            MetadataScope scope = scope(dbId, conn);
            try (ResultSet rs = meta.getTables(scope.catalog(), pattern(meta, scope.schema()), "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tableSchema = rs.getString("TABLE_SCHEM");
                    if (scope.schema() != null && !scope.schema().equals(tableSchema)) continue;
                    if (tableSchema != null && (tableSchema.equalsIgnoreCase("sys") || tableSchema.equalsIgnoreCase("information_schema"))) {
                        continue;
                    }
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            return tables.stream().distinct().sorted().toList();
        });
    }

    @Override
    public List<ColumnResponse> getColumns(String dbId, String tableName) {
        DatabaseIdentifiers.validate(tableName);
        return readMetadata(dbId, conn -> {
            List<ColumnResponse> columns = new ArrayList<>();
            DatabaseMetaData meta = conn.getMetaData();
            MetadataScope scope = scope(dbId, conn);
            try (ResultSet rs = meta.getColumns(scope.catalog(), pattern(meta, scope.schema()), pattern(meta, tableName), "%")) {
                while (rs.next()) {
                    if (!tableName.equals(rs.getString("TABLE_NAME"))) continue;
                    if (scope.schema() != null && !scope.schema().equals(rs.getString("TABLE_SCHEM"))) continue;
                    columns.add(new ColumnResponse(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("TYPE_NAME")
                    ));
                }
            }
            return columns;
        });
    }

    private <T> T readMetadata(String dbId, MetadataRead<T> operation) {
        try {
            return DatabaseReadRetry.read(dbId, dataSourceRegistry.getDatabaseLabel(dbId), () -> {
                DataSource source = dataSourceRegistry.getDataSource(dbId);
                try (Connection connection = source.getConnection()) {
                    try { return operation.read(connection); }
                    catch (SQLException | RuntimeException failure) {
                        DatabaseReadRetry.discardBrokenConnection(source, connection, failure);
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw new DataAccessResourceFailureException("Database metadata read failed.", failure);
                }
            });
        } catch (DataAccessException failure) {
            throw new DatabaseConnectionException("Không thể đọc cấu trúc nguồn “" + dataSourceRegistry.getDatabaseLabel(dbId)
                    + "”. Kiểm tra thông tin xác thực, quyền đọc và kết nối database.", failure);
        }
    }

    @FunctionalInterface
    private interface MetadataRead<T> { T read(Connection connection) throws SQLException; }

    private MetadataScope scope(String dbId, Connection connection) throws SQLException {
        return adapters.get(dataSourceRegistry.getDatabaseType(dbId))
                .metadataScope(connection, dataSourceRegistry.getDatabaseSchema(dbId));
    }

    private String pattern(DatabaseMetaData metadata, String value) throws SQLException {
        if (value == null) return null;
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
