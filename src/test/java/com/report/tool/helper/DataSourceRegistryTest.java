package com.report.tool.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.database.DatabaseAdapters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DataSourceRegistryTest {
    @TempDir Path directory;

    @Test
    void removesLegacyPasswordsAndPreservesDisconnectedConfigurations() throws Exception {
        Path file = directory.resolve("connections.json");
        DBConnectionRequest config = DBConnectionRequest.builder().id("db_demo").name("Demo").dbType("POSTGRESQL")
                .host("localhost").port(5432).database("demo").username("reader").password("test-secret").build();
        new ObjectMapper().writeValue(file.toFile(), Map.of("db_demo", config));
        DataSourceRegistry registry = new DataSourceRegistry(file.toString(), new DatabaseAdapters()) {
            @Override public void connectInternal(DBConnectionRequest request, boolean persist) {
                throw new DatabaseConnectionException("Simulated offline database");
            }
        };
        registry.loadSavedConnections();
        assertThat(Files.readString(file)).doesNotContain("test-secret").contains("db_demo");
        assertThat(registry.getAllDatabases()).singleElement().satisfies(saved -> {
            assertThat(saved.getPassword()).isNull();
            assertThat(saved.getUsername()).isEqualTo("reader");
        });
        assertThat(registry.isConnected("db_demo")).isFalse();
        assertThat(config.toString()).doesNotContain("test-secret");
        registry.removeDatabase("db_demo");
        assertThat(new ObjectMapper().readTree(file.toFile()).isEmpty()).isTrue();
    }
}
