package com.report.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.controller.ReportWorkspaceController;
import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.helper.DatabaseReadRetry;
import com.report.tool.service.*;
import com.report.tool.service.impl.ExcelTemplateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import java.nio.file.*;
import java.sql.SQLRecoverableException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ReportJobRetryTest {
    @TempDir Path directory;

    @Test
    void exportShowsReconnectStateAndRetainsRecoveryInformation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ExcelTemplateServiceImpl templates = new ExcelTemplateServiceImpl(directory.resolve("templates").toString());
        ReportOutputService output = new ReportOutputService(templates); ReportWorkbookService writer = new ReportWorkbookService(templates);
        ReportJobService jobs = new ReportJobService(engine(attempts, false), output, writer, new ObjectMapper(), directory.resolve("exports").toString());
        try {
            String id = jobs.start(config()).id();
            ReportJobService.Status reconnecting = await(jobs, id, Set.of("RECONNECTING", "FAILED", "READY"));
            assertThat(reconnecting.state()).isEqualTo("RECONNECTING"); assertThat(reconnecting.message()).contains("Orders", "1/1");
            ReportJobService.Status ready = await(jobs, id, Set.of("READY", "FAILED"));
            assertThat(ready.state()).isEqualTo("READY"); assertThat(attempts.get()).isEqualTo(2);
            assertThat(ready.recoveryMessages()).singleElement().asString().contains("Orders", "1/1");
            assertThat(Files.exists(jobs.download(id, true))).isTrue();
        } finally { jobs.close(); }
    }

    @Test
    void persistentLossFailsJobWithoutLeavingDownloadableOutput() throws Exception {
        AtomicInteger attempts = new AtomicInteger(); ExcelTemplateServiceImpl templates = new ExcelTemplateServiceImpl(directory.resolve("templates").toString());
        ReportJobService jobs = new ReportJobService(engine(attempts, true), new ReportOutputService(templates),
                new ReportWorkbookService(templates), new ObjectMapper(), directory.resolve("exports").toString());
        try {
            String id = jobs.start(config()).id(); ReportJobService.Status failed = await(jobs, id, Set.of("READY", "FAILED"));
            assertThat(failed.state()).isEqualTo("FAILED"); assertThat(failed.message()).contains("Orders", "một lần thử lại");
            assertThat(attempts.get()).isEqualTo(2); assertThatThrownBy(() -> jobs.download(id, true)).isInstanceOf(RuntimeException.class);
            try (java.util.stream.Stream<Path> files = Files.list(directory.resolve("exports"))) { assertThat(files.toList()).isEmpty(); }
        } finally { jobs.close(); }
    }

    @Test
    void previewReportsRecoveryAndGetsAnIndependentRetryBudget() {
        AtomicInteger attempts = new AtomicInteger(); ExcelTemplateServiceImpl templates = new ExcelTemplateServiceImpl(directory.resolve("templates").toString());
        ReportWorkspaceController controller = new ReportWorkspaceController(engine(attempts, false), new ReportOutputService(templates), new ReportWorkbookService(templates), null);
        assertThat(controller.preview(config()).getData().recoveryMessages()).hasSize(1);
        attempts.set(0);
        assertThat(controller.preview(config()).getData().recoveryMessages()).hasSize(1);
        assertThat(attempts.get()).isEqualTo(2);
    }

    private ReportConfigRequest config() {
        return ReportConfigRequest.builder().outputMode("TABLE").baseTable(ReportConfigRequest.BaseTable.builder()
                .dbId("db").tableName("orders").columns(List.of("id")).build())
                .outputColumns(List.of(ReportConfigRequest.OutputColumn.builder().field("orders.id").title("ID").build())).build();
    }
    private ReportEngineService engine(AtomicInteger attempts, boolean alwaysFail) {
        return new ReportEngineService() {
            public List<Map<String, Object>> executeReport(ReportConfigRequest config) {
                return DatabaseReadRetry.read("db", "Orders", () -> {
                    if (attempts.incrementAndGet() == 1 || alwaysFail) throw new DataAccessResourceFailureException("Simulated connection loss",
                            new SQLRecoverableException("Closed", "08006"));
                    return List.of(Map.of("orders.id", 1));
                });
            }
            public List<Map<String, Object>> previewReport(ReportConfigRequest config) { return executeReport(config); }
        };
    }
    private ReportJobService.Status await(ReportJobService jobs, String id, Set<String> states) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); ReportJobService.Status status = jobs.status(id);
        while (!states.contains(status.state()) && System.nanoTime() < deadline) { Thread.sleep(10); status = jobs.status(id); }
        return status;
    }
}
