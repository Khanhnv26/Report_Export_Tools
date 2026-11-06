package com.report.tool.service.impl;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.helper.DataSourceRegistry;
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

    @Override
    public DatabaseResponse addDataBase(DBConnectionRequest request) {
        DBConnectionRequest saved = dataSourceRegistry.connectAndRegister(request);
        return DatabaseResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .databaseName(saved.getDatabase())
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
        DataSource ds = dataSourceRegistry.getDataSource(dbId);
        List<String> tables = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tableSchema = rs.getString("TABLE_SCHEM");
                    if (tableSchema != null && (tableSchema.equalsIgnoreCase("sys") || tableSchema.equalsIgnoreCase("information_schema"))) {
                        continue;
                    }
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            log.error("Lỗi khi đọc danh sách bảng từ Database [{}]: {}", dbId, e.getMessage());
            throw new DatabaseConnectionException("Không thể đọc danh sách bảng từ Database [" + dbId + "]: " + e.getMessage(), e);
        }
        return tables;
    }

    @Override
    public List<ColumnResponse> getColumns(String dbId, String tableName) {
        DataSource ds = dataSourceRegistry.getDataSource(dbId);
        List<ColumnResponse> columns = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    columns.add(new ColumnResponse(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("TYPE_NAME")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Lỗi khi đọc danh sách cột từ bảng [{}] của DB [{}]: {}", tableName, dbId, e.getMessage());
            throw new DatabaseConnectionException("Không thể đọc danh sách cột từ bảng [" + tableName + "]: " + e.getMessage(), e);
        }
        return columns;
    }
}
