package com.report.tool.database;

import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.service.impl.MetadataServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DatabaseMetadataTest {
    @TempDir Path directory;

    @Test
    void metadataKeepsSchemaAndUnderscoreTableNamesExact() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA report_schema");
        jdbc.execute("CREATE SCHEMA reportXschema");
        jdbc.execute("CREATE TABLE report_schema.user_data (correct INTEGER)");
        jdbc.execute("CREATE TABLE report_schema.userXdata (wrong INTEGER)");
        jdbc.execute("CREATE TABLE reportXschema.other (wrong INTEGER)");
        DataSourceRegistry registry = new DataSourceRegistry(directory.resolve("connections.json").toString(), new DatabaseAdapters()) {
            @Override public DataSource getDataSource(String id) { return source; }
            @Override public String getDatabaseType(String id) { return "POSTGRESQL"; }
            @Override public String getDatabaseSchema(String id) { return "report_schema"; }
        };
        MetadataServiceImpl metadata = new MetadataServiceImpl(registry, new DatabaseAdapters());
        assertThat(metadata.getTables("db")).containsExactly("user_data", "userxdata");
        assertThat(metadata.getColumns("db", "user_data")).singleElement()
                .satisfies(column -> assertThat(column.getName()).isEqualTo("correct"));
    }
}
