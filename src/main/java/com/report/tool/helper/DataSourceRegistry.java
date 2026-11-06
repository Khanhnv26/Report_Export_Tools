package com.report.tool.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.exception.DatabaseNotFoundException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DataSourceRegistry {

    private final File storageFile = new File("connections.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, DBConnectionRequest> savedConfigs = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final Map<String, NamedParameterJdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadSavedConnections() {
        if (!storageFile.exists()) return;

        try {
            Map<String, DBConnectionRequest> loaded = objectMapper.readValue(
                    storageFile, new TypeReference<Map<String, DBConnectionRequest>>() {});
            loaded.values().forEach(request -> {
                try {
                    connectInternal(request, false);
                } catch (Exception e) {
                    log.warn("Không thể tự kết nối lại DB {}: {}", request.getName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Lỗi đọc file connections.json: {}", e.getMessage());
        }
    }

    public DBConnectionRequest connectAndRegister(DBConnectionRequest request) {
        if (request.getId() == null || request.getId().isBlank()) {
            request.setId("db_" + UUID.randomUUID().toString().substring(0, 6));
        }
        connectInternal(request, true);
        return request;
    }

    public void connectInternal(DBConnectionRequest request, boolean persist) {
        String jdbcUrl = buildJdbcUrl(request);
        String driver = getDriverClassName(request.getDbType());

        HikariConfig config = new HikariConfig();
        config.setPoolName("pool-" + request.getId());
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(request.getUsername());
        config.setPassword(request.getPassword());
        config.setDriverClassName(driver);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(8000);
        config.setReadOnly(true);

        HikariDataSource dataSource;
        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(5)) {
                    dataSource.close();
                    throw new DatabaseConnectionException("Kết nối Database thất bại: Connection không hợp lệ hoặc timeout!");
                }
            }
        } catch (Exception e) {
            throw new DatabaseConnectionException("Không thể kết nối đến Database [" + request.getName() + "]: " + e.getMessage(), e);
        }

        HikariDataSource old = dataSources.put(request.getId(), dataSource);
        if (old != null) {
            old.close();
        }

        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(60);
        jdbcTemplates.put(request.getId(), jdbc);
        savedConfigs.put(request.getId(), request);

        if (persist) {
            saveToDisk();
        }
        log.info("Đã kết nối thành công Database: {} ({})", request.getName(), jdbcUrl);
    }

    private void saveToDisk() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, savedConfigs);
        } catch (Exception e) {
            log.warn("Lỗi lưu file connections.json: {}", e.getMessage());
        }
    }

    public void removeDatabase(String dbId) {
        if (!savedConfigs.containsKey(dbId)) {
            throw new DatabaseNotFoundException(dbId);
        }
        savedConfigs.remove(dbId);
        jdbcTemplates.remove(dbId);
        HikariDataSource ds = dataSources.remove(dbId);
        if (ds != null) {
            ds.close();
        }
        saveToDisk();
        log.info("Đã xóa kết nối Database: {}", dbId);
    }

    private String buildJdbcUrl(DBConnectionRequest request) {
        String type = request.getDbType() != null ? request.getDbType().toUpperCase() : "POSTGRESQL";
        int port = request.getPort() > 0 ? request.getPort() : defaultPort(type);
        return switch (type) {
            case "MYSQL" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    request.getHost(), port, request.getDatabase());
            case "POSTGRESQL" -> String.format("jdbc:postgresql://%s:%d/%s",
                    request.getHost(), port, request.getDatabase());
            case "SQLSERVER", "MSSQL" -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true;",
                    request.getHost(), port, request.getDatabase());
            default -> throw new DatabaseConnectionException("Chưa hỗ trợ loại Database: " + type);
        };
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "MYSQL" -> 3306;
            case "SQLSERVER", "MSSQL" -> 1433;
            default -> 5432;
        };
    }

    private String getDriverClassName(String dbType) {
        String type = dbType != null ? dbType.toUpperCase() : "POSTGRESQL";
        return switch (type) {
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "SQLSERVER", "MSSQL" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new DatabaseConnectionException("Chưa hỗ trợ loại Database: " + type);
        };
    }

    public DataSource getDataSource(String dbId) {
        DataSource ds = dataSources.get(dbId);
        if (ds == null) {
            throw new DatabaseNotFoundException(dbId);
        }
        return ds;
    }

    public NamedParameterJdbcTemplate getJdbc(String dbId) {
        NamedParameterJdbcTemplate jdbc = jdbcTemplates.get(dbId);
        if (jdbc == null) {
            throw new DatabaseNotFoundException(dbId);
        }
        return jdbc;
    }

    public Collection<DBConnectionRequest> getAllDatabases() {
        return savedConfigs.values();
    }

    @PreDestroy
    public void closeAll() {
        dataSources.values().forEach(HikariDataSource::close);
    }
}
