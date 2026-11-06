package com.report.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.controller.ReportWorkspaceController;
import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.OutputColumn;
import com.report.tool.dto.request.ReportConfigRequest.Filter;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.exception.ReportBusyException;
import com.report.tool.service.*;
import com.report.tool.service.impl.ExcelTemplateServiceImpl;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ReportWorkspaceTest {
    @TempDir Path directory;
    private ExcelTemplateServiceImpl templates() { return new ExcelTemplateServiceImpl(directory.resolve("templates").toString()); }
    private ReportConfigRequest config() {
        return ReportConfigRequest.builder().outputMode("TABLE")
                .baseTable(ReportConfigRequest.BaseTable.builder().dbId("db").tableName("orders")
                        .columns(List.of("id", "amount", "date")).build())
                .outputColumns(new ArrayList<>(List.of(
                        OutputColumn.builder().field("orders.id").title("Mã đơn").type("TEXT").build(),
                        OutputColumn.builder().field("orders.amount").title("Thành tiền").type("NUMBER").format("DECIMAL").build(),
                        OutputColumn.builder().field("orders.date").title("Ngày").type("DATE").build()))).build();
    }
    private List<Map<String, Object>> rows() {
        return List.of(Map.of("orders.id", 9223372036854775807L, "orders.amount", new BigDecimal("1234.50"), "orders.date", "2026-11-06"));
    }
    @Test
    void previewAndStreamingWorkbookUseTheSameTypesFormatsAndColumnOrder() throws Exception {
        ExcelTemplateServiceImpl templates = templates();
        ReportOutputService output = new ReportOutputService(templates);
        ReportWorkbookService writer = new ReportWorkbookService(templates);
        ReportConfigRequest config = config();
        Collections.swap(config.getOutputColumns(), 0, 1);
        ReportOutputService.PreparedReport prepared = output.prepare(config, rows());
        List<List<String>> preview = writer.preview(config, prepared);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.write(config, prepared, bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Thành tiền");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("9223372036854775807");
            for (int col = 0; col < 3; col++) assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(col))).isEqualTo(preview.get(0).get(col));
        }
    }
    @Test
    void filtersAndSortApplyBeforePreviewAndInvalidConversionsBlockExport() {
        ReportOutputService output = new ReportOutputService(templates());
        ReportConfigRequest config = config();
        config.setFilters(List.of(Filter.builder().field("orders.amount").operator("GTE").value("10").build()));
        config.setSortField("orders.amount"); config.setSortDirection("DESC");
        List<Map<String, Object>> input = new ArrayList<>();
        input.add(Map.of("orders.id", "A", "orders.amount", 2));
        input.add(Map.of("orders.id", "B", "orders.amount", 30));
        input.add(Map.of("orders.id", "C", "orders.amount", 20));
        ReportOutputService.PreparedReport result = output.prepare(config, input);
        assertThat(result.rows()).extracting(row -> row.get(0)).containsExactly("B", "C");
        config.getOutputColumns().get(0).setType("NUMBER");
        assertThatThrownBy(() -> output.prepare(config, input)).isInstanceOf(InvalidReportConfigException.class).hasMessageContaining("Dòng");
        config.setFilters(List.of()); config.getOutputColumns().get(0).setType("TEXT");
        assertThatThrownBy(() -> output.prepare(config, List.of(Map.of("orders.date", "31/02/2026"))))
                .isInstanceOf(InvalidReportConfigException.class);
    }
    @Test
    void templateFormattingMatchesPreviewAndOriginalTemplateRemainsUntouched() throws Exception {
        ExcelTemplateServiceImpl templates = templates();
        String id;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("Theo mẫu");
            Cell cell = sheet.createRow(1).createCell(0); cell.setCellValue(0);
            CellStyle style = workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.000")); cell.setCellStyle(style);
            workbook.write(bytes);
            id = templates.saveTemplate(new MockMultipartFile("file", "template.xlsx", "", bytes.toByteArray()));
        }
        byte[] original = Files.readAllBytes(directory.resolve("templates").resolve(id));
        ReportConfigRequest config = config(); config.setTemplateId(id); config.setOutputMode("TEMPLATE");
        config.setOutputColumns(List.of(OutputColumn.builder().letter("A").field("orders.amount").title("Ignored").build()));
        ReportOutputService.PreparedReport prepared = new ReportOutputService(templates).prepare(config, rows());
        ReportWorkbookService writer = new ReportWorkbookService(templates);
        String preview = writer.preview(config, prepared).get(0).get(0);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); writer.write(config, prepared, result);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result.toByteArray()))) {
            assertThat(new DataFormatter(Locale.forLanguageTag("vi-VN")).formatCellValue(workbook.getSheetAt(0).getRow(1).getCell(0))).isEqualTo(preview);
        }
        assertThat(Files.readAllBytes(directory.resolve("templates").resolve(id))).isEqualTo(original);
    }
    @Test
    void jobsRunAsynchronouslyRequireWarningAcknowledgementAndCanBeRemoved() throws Exception {
        ExcelTemplateServiceImpl templates = templates(); ReportOutputService output = new ReportOutputService(templates);
        ReportWorkbookService writer = new ReportWorkbookService(templates);
        ReportEngineService engine = new ReportEngineService() {
            public List<Map<String, Object>> executeReport(ReportConfigRequest config) { return rows(); }
            public List<Map<String, Object>> previewReport(ReportConfigRequest config) { return rows(); }
        };
        try (JobScope scope = new JobScope(new ReportJobService(engine, output, writer, new ObjectMapper(), directory.resolve("exports").toString()))) {
            ReportConfigRequest config = config();
            config.setJoins(List.of(ReportConfigRequest.JoinTable.builder().tableName("customers").alias("crm").targetKey("id").build()));
            ReportJobService.Status accepted = scope.jobs.start(config);
            config.getOutputColumns().get(0).setTitle("Changed after submit");
            ReportJobService.Status ready = await(scope.jobs, accepted.id());
            assertThat(ready.state()).isEqualTo("READY"); assertThat(ready.warnings()).isNotEmpty();
            assertThatThrownBy(() -> scope.jobs.download(accepted.id(), false)).isInstanceOf(InvalidReportConfigException.class);
            Path file = scope.jobs.download(accepted.id(), true);
            try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
                assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Mã đơn");
            }
            scope.jobs.cancel(accepted.id()); assertThat(Files.exists(file)).isFalse();
        }
    }
    @Test
    void boundedQueueRejectsExcessWorkAndQueuedJobsAreCancellable() throws Exception {
        CountDownLatch entered = new CountDownLatch(2); CountDownLatch release = new CountDownLatch(1);
        ReportEngineService engine = new ReportEngineService() {
            public List<Map<String, Object>> executeReport(ReportConfigRequest config) {
                entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return rows();
            }
            public List<Map<String, Object>> previewReport(ReportConfigRequest config) { return rows(); }
        };
        ExcelTemplateServiceImpl templates = templates();
        try (JobScope scope = new JobScope(new ReportJobService(engine, new ReportOutputService(templates), new ReportWorkbookService(templates), new ObjectMapper(), directory.resolve("exports").toString()))) {
            List<String> ids = new ArrayList<>(); for (int index = 0; index < 6; index++) ids.add(scope.jobs.start(config()).id());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> scope.jobs.start(config())).isInstanceOf(ReportBusyException.class);
            assertThat(scope.jobs.cancel(ids.get(5)).state()).isEqualTo("CANCELLED");
            assertThat(scope.jobs.start(config()).id()).isNotBlank();
            ids.forEach(scope.jobs::cancel);
            release.countDown();
        } finally { release.countDown(); }
    }
    @Test
    void fullPreviewReadsBeyondSampleWhenFilteringAndCancellationCallsJdbcStatement() {
        AtomicInteger fullReads = new AtomicInteger();
        ReportEngineService engine = new ReportEngineService() {
            public List<Map<String, Object>> executeReport(ReportConfigRequest config) { fullReads.incrementAndGet(); return rows(); }
            public List<Map<String, Object>> previewReport(ReportConfigRequest config) { return List.of(); }
        };
        ExcelTemplateServiceImpl templates = templates();
        ReportWorkspaceController controller = new ReportWorkspaceController(engine, new ReportOutputService(templates), new ReportWorkbookService(templates), null);
        ReportConfigRequest config = config(); config.setSortField("orders.amount");
        assertThat(controller.preview(config).getData().sampled()).isFalse(); assertThat(fullReads.get()).isEqualTo(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> { if (method.getName().equals("cancel")) cancelled.set(true); return null; });
        ReportCancellation control = new ReportCancellation();
        assertThatThrownBy(() -> control.run(() -> { ReportCancellation.watch(statement); control.cancel(); ReportCancellation.check(); return null; })).isInstanceOf(CancellationException.class);
        assertThat(cancelled).isTrue(); ReportCancellation.check();
    }
    private ReportJobService.Status await(ReportJobService jobs, String id) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ReportJobService.Status status = jobs.status(id);
        while (!Set.of("READY", "FAILED", "CANCELLED").contains(status.state()) && System.nanoTime() < end) { Thread.sleep(10); status = jobs.status(id); }
        return status;
    }
    private record JobScope(ReportJobService jobs) implements AutoCloseable { public void close() { jobs.close(); } }
}
