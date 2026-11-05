package com.report.tool.service.impl;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.service.MetadataService;
import lombok.RequiredArgsConstructor;
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
public class MetadataServiceImpl implements MetadataService {

    private final DataSourceRegistry dataSourceRegistry;

    @Override
    public DatabaseResponse addDataBase(DBConnectionRequest request) throws Exception {
        DBConnectionRequest saved = dataSourceRegistry.connectAndRegister(request);
        return DatabaseResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .databaseName(saved.getDatabase())
                .dbType(saved.getDbType())
                .host(saved.getHost())
                .port(saved.getPort())
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
                        .build())
                .toList();
    }

    @Override
    public List<String> getTables(String dbId) throws SQLException {
        DataSource ds =dataSourceRegistry.getDataSource(dbId);
        List<String> tables = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            try (ResultSet rs = meta.getTables(catalog, schema, "%", new String [] {"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    tables.add(tableName);
                }
            }
        }
        return tables;
    }

    @Override
    public List<ColumnResponse> getColumns(String dbId, String tableName) throws SQLException {
        DataSource ds = dataSourceRegistry.getDataSource(dbId);
        List<ColumnResponse> columns = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String columnType = rs.getString("TYPE_NAME");
                    columns.add(new ColumnResponse(columnName, columnType));
                }
            }
        }
        return columns;
    }
}
