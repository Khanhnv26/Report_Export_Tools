package com.report.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.config.AccessControlFilter;
import com.report.tool.controller.ReportController;
import com.report.tool.controller.TemplateController;
import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.BaseTable;
import com.report.tool.dto.request.ReportConfigRequest.JoinTable;
import com.report.tool.exception.GlobalExceptionHandler;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.SqlBuilder;
import com.report.tool.database.DatabaseAdapters;
import com.report.tool.service.impl.ExcelTemplateServiceImpl;
import com.report.tool.service.impl.ReportEngineServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportWorkflowTest {
    @TempDir Path directory;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        JdbcTemplate orders = database();
        JdbcTemplate crm = database();
        orders.execute("CREATE TABLE orders (id BIGINT, customer_id BIGINT, created_at TIMESTAMP)");
        orders.update("INSERT INTO orders VALUES (9223372036854775807, 7, '2026-11-06 23:59:59')");
        crm.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100))");
        crm.update("INSERT INTO users VALUES (7, 'Alice'), (7, 'Bob')");
        DataSourceRegistry registry = new DataSourceRegistry(directory.resolve("connections.json").toString(), new DatabaseAdapters()) {
            @Override public NamedParameterJdbcTemplate getJdbc(String id) {
                return new NamedParameterJdbcTemplate(id.equals("orders_db") ? orders : crm);
            }
            @Override public String getDatabaseType(String id) { return "POSTGRESQL"; }
            @Override public String getDatabaseSchema(String id) { return null; }
        };
        mvc = MockMvcBuilders.standaloneSetup(
                        new ReportController(new ReportEngineServiceImpl(registry, new SqlBuilder(new DatabaseAdapters())),
                                new ExcelTemplateServiceImpl(directory.toString())),
                        new TemplateController(new ExcelTemplateServiceImpl(directory.toString())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AccessControlFilter("127.0.0.1", "report", "")).build();
    }

    private JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL", "sa", ""));
    }

    private ReportConfigRequest request(String template) {
        return ReportConfigRequest.builder().templateId(template)
                .baseTable(BaseTable.builder().dbId("orders_db").tableName("orders").columns(List.of("id"))
                        .filterColumn("created_at").fromDate("2026-11-06").toDate("2026-11-06").build())
                .joins(List.of(JoinTable.builder().dbId("crm").tableName("users").alias("crm")
                        .columns(List.of("name")).sourceKey("customer_id").targetKey("id").build()))
                .mapping(Map.of("A", "orders.id", "B", "crm.name")).build();
    }

    private String upload() throws Exception {
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row header = workbook.createSheet().createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Name");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        MvcResult result = mvc.perform(multipart("/api/templates").file(new MockMultipartFile("file", "report.xlsx", "", bytes)))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/templateId").asText();
    }

    @Test
    void uploadsPreviewsAndExportsEveryMatchWithExactIds() throws Exception {
        String template = upload();
        mvc.perform(get("/api/templates/{id}/columns", template)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].title").value("Name"));
        String body = mapper.writeValueAsString(request(template));
        mvc.perform(post("/api/reports/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0]['orders.id']").value("9223372036854775807"));
        MvcResult result = mvc.perform(post("/api/reports/export").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("9223372036854775807");
            assertThat(List.of(sheet.getRow(1).getCell(1).getStringCellValue(), sheet.getRow(2).getCell(1).getStringCellValue()))
                    .containsExactlyInAnyOrder("Alice", "Bob");
        }
    }

    @Test
    void invalidMappingReturnsJsonErrorInsteadOfBrokenExcelDownload() throws Exception {
        ReportConfigRequest request = request(upload());
        request.setMapping(Map.of("A", "missing.column"));
        mvc.perform(post("/api/reports/export").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
