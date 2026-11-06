package com.report.tool.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.database.DatabaseAdapters;
import com.report.tool.database.DatabaseAdapter;
import com.report.tool.database.DatabaseIdentifiers;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.exception.DatabaseNotFoundException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DataSourceRegistry {

    private final File storageFile;
    private final DatabaseAdapters adapters;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, DBConnectionRequest> savedConfigs = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final Map<String, NamedParameterJdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();

    public DataSourceRegistry(@Value("${report.connections-file:connections.json}") String connectionsFile,
                              DatabaseAdapters adapters) {
        storageFile = new File(connectionsFile);
        this.adapters = adapters;
    }

    @PostConstruct
    public void loadSavedConnections() {
        if (!storageFile.exists()) return;

        try {
            Map<String, DBConnectionRequest> loaded = objectMapper.readValue(
                    storageFile, new TypeReference<Map<String, DBConnectionRequest>>() {});
            loaded.forEach((id, request) -> savedConfigs.put(id, withoutPassword(request)));
            // Migrate legacy files before reconnecting: passwords never remain on disk.
            saveToDisk();
            loaded.values().forEach(request -> {
                try {
                    String password = System.getenv(passwordVariable(request.getId()));
                    if (password != null) request.setPassword(password);
                    connectInternal(request, false);
                } catch (Exception e) {
                    log.warn("Không thể tự kết nối lại DB {}. Hãy kết nối lại hoặc cấu hình biến môi trường {}.",
                            request.getId(), passwordVariable(request.getId()));
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Không thể đọc hoặc làm sạch file cấu hình kết nối.", e);
        }
    }

    public synchronized DBConnectionRequest connectAndRegister(DBConnectionRequest request) {
        if (request == null) throw new DatabaseConnectionException("Cấu hình kết nối không hợp lệ!");
        if (request.getId() == null || request.getId().isBlank()) {
            request.setId("db_" + UUID.randomUUID().toString().replace("-", ""));
        }
        connectInternal(request, true);
        return request;
    }

    public void connectInternal(DBConnectionRequest request, boolean persist) {
        if (request.getId() == null || !request.getId().matches("[a-zA-Z0-9_-]+")
                || request.getHost() == null || !request.getHost().matches("[a-zA-Z0-9.\\-:\\[\\]]+")
                || request.getDatabase() == null || !request.getDatabase().matches("[\\p{L}\\p{N}_$# .-]+")
                || request.getPort() < 0 || request.getPort() > 65535) {
            throw new DatabaseConnectionException("ID, host, cổng hoặc tên database không hợp lệ!");
        }
        DatabaseAdapter adapter = adapters.get(request.getDbType());
        if (request.getSchema() != null && !request.getSchema().isBlank()) DatabaseIdentifiers.validate(request.getSchema());
        request.setDbType(adapter.type().id());
        request.setPort(adapter.effectivePort(request));
        if (!adapter.type().supportsSchema()) request.setSchema(null);
        String jdbcUrl = adapter.jdbcUrl(request);
        String driver = adapter.type().driverClassName();

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

        HikariDataSource dataSource = null;
        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(5)) {
                    dataSource.close();
                    throw new DatabaseConnectionException("Kết nối Database thất bại: Connection không hợp lệ hoặc timeout!");
                }
                request.setSchema(adapter.metadataScope(conn, request.getSchema()).schema());
                if (request.getSchema() != null) DatabaseIdentifiers.validate(request.getSchema());
            }
        } catch (Exception e) {
            if (dataSource != null) dataSource.close();
            throw new DatabaseConnectionException("Không thể kết nối database. Kiểm tra thông số và quyền truy cập.", e);
        }

        HikariDataSource old = dataSources.put(request.getId(), dataSource);
        if (old != null) {
            old.close();
        }

        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(60);
        jdbcTemplates.put(request.getId(), jdbc);
        savedConfigs.put(request.getId(), withoutPassword(request));

        if (persist) {
            saveToDisk();
        }
        log.info("Đã kết nối thành công Database: {}", request.getId());
    }

    private synchronized void saveToDisk() {
        Path temporary = null;
        try {
            Path target = storageFile.toPath().toAbsolutePath();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "connections-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), savedConfigs);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new DatabaseConnectionException("Không thể lưu cấu hình kết nối.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }

    static String passwordVariable(String id) {
        return "REPORT_DB_PASSWORD_" + id.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private DBConnectionRequest withoutPassword(DBConnectionRequest request) {
        return DBConnectionRequest.builder().id(request.getId()).name(request.getName()).dbType(request.getDbType())
                .host(request.getHost()).port(request.getPort()).database(request.getDatabase())
                .schema(request.getSchema())
                .username(request.getUsername()).build();
    }

    public synchronized void removeDatabase(String dbId) {
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

    public String getDatabaseType(String dbId) {
        DBConnectionRequest config = savedConfigs.get(dbId);
        if (config == null) throw new DatabaseNotFoundException(dbId);
        return config.getDbType();
    }

    public String getDatabaseLabel(String dbId) {
        DBConnectionRequest config = savedConfigs.get(dbId);
        return config == null || config.getName() == null || config.getName().isBlank() ? dbId : config.getName();
    }

    public String getDatabaseSchema(String dbId) {
        DBConnectionRequest config = savedConfigs.get(dbId);
        if (config == null) throw new DatabaseNotFoundException(dbId);
        return config.getSchema();
    }

    public boolean isConnected(String dbId) {
        return jdbcTemplates.containsKey(dbId);
    }

    public Collection<DBConnectionRequest> getAllDatabases() {
        return savedConfigs.values();
    }

    @PreDestroy
    public void closeAll() {
        dataSources.values().forEach(HikariDataSource::close);
    }
}
