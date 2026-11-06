package com.report.tool;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.service.ReportOutputService;
import com.report.tool.service.ReportWorkbookService;
import com.report.tool.service.impl.ExcelTemplateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.OutputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportPerformanceTest {
    @TempDir Path directory;
    @Test
    void writesFiftyThousandRowsWithAStreamingWorkbook() throws Exception {
        ExcelTemplateServiceImpl templates = new ExcelTemplateServiceImpl(directory.resolve("templates").toString());
        ReportConfigRequest request = ReportConfigRequest.builder().outputMode("TABLE")
                .baseTable(ReportConfigRequest.BaseTable.builder().tableName("orders").columns(List.of("id", "name", "amount")).build())
                .outputColumns(List.of(
                        ReportConfigRequest.OutputColumn.builder().field("orders.id").title("Mã đơn").type("TEXT").build(),
                        ReportConfigRequest.OutputColumn.builder().field("orders.name").title("Khách hàng").build(),
                        ReportConfigRequest.OutputColumn.builder().field("orders.amount").title("Giá trị").format("DECIMAL").build())).build();
        List<Map<String, Object>> input = new ArrayList<>(50000);
        for (int row = 0; row < 50000; row++) input.add(Map.of("orders.id", 1000000000000000000L + row,
                "orders.name", "Khách hàng " + row, "orders.amount", row * 10));
        long start = System.nanoTime();
        ReportOutputService.PreparedReport prepared = new ReportOutputService(templates).prepare(request, input);
        Path file = directory.resolve("50000-rows.xlsx");
        try (OutputStream stream = Files.newOutputStream(file)) { new ReportWorkbookService(templates).write(request, prepared, stream); }
        long elapsed = (System.nanoTime() - start) / 1000000;
        assertThat(prepared.rows()).hasSize(50000); assertThat(Files.size(file)).isGreaterThan(10000);
        System.out.println("REPORT_BENCHMARK rows=50000 columns=3 elapsed_ms=" + elapsed + " xlsx_bytes=" + Files.size(file)
                + " scope=output-conversion-and-workbook-only synthetic=true");
    }
}
