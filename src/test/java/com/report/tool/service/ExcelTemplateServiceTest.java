package com.report.tool.service;

import com.report.tool.exception.TemplateProcessingException;
import com.report.tool.service.impl.ExcelTemplateServiceImpl;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ExcelTemplateServiceTest {
    @TempDir Path directory;
    ExcelTemplateServiceImpl service;
    String template;

    @BeforeEach
    void setup() throws Exception {
        service = new ExcelTemplateServiceImpl(directory.toString());
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("ID");
            row.createCell(1).setCellValue("Amount");
            workbook.write(out);
            template = service.saveTemplate(new MockMultipartFile("file", "template.xlsx", "", out.toByteArray()));
        }
    }

    @Test
    void preservesLongIdsAndPreciseDecimalsWithoutChangingOrdinaryNumbers() throws Exception {
        Map<String, Object> row = Map.<String, Object>of("id", 9223372036854775807L, "money", new BigDecimal("123.45"),
                "precise", new BigDecimal("123456789012345.67"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.fillTemplate(template, 0, List.of(row), Map.of("A", "id", "B", "money", "C", "precise"), output);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            Row result = workbook.getSheetAt(0).getRow(1);
            assertThat(result.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(result.getCell(0).getStringCellValue()).isEqualTo("9223372036854775807");
            assertThat(result.getCell(1).getNumericCellValue()).isEqualTo(123.45);
            assertThat(result.getCell(2).getStringCellValue()).isEqualTo("123456789012345.67");
        }
    }

    @Test
    void rejectsTraversalAndInvalidMappings() {
        assertThatThrownBy(() -> service.readHeaders("../other.xlsx", 0)).isInstanceOf(TemplateProcessingException.class);
        assertThatThrownBy(() -> service.fillTemplate(template, 0, List.of(Map.of("id", 1)),
                Map.of("A", "missing"), new ByteArrayOutputStream())).isInstanceOf(TemplateProcessingException.class);
        assertThatThrownBy(() -> service.fillTemplate(template, 0, List.of(), Map.of("XFE", "id"),
                new ByteArrayOutputStream())).isInstanceOf(TemplateProcessingException.class);
        assertThatThrownBy(() -> service.readHeaders(template, -1)).isInstanceOf(TemplateProcessingException.class);
    }

    @Test
    void uploadUsesServerGeneratedName() throws Exception {
        String name = service.saveTemplate(new MockMultipartFile("file", "../../elsewhere.xlsx", "", Files.readAllBytes(directory.resolve(template))));
        assertThat(directory.resolve(name).getParent()).isEqualTo(directory);
        assertThat(name).doesNotContain("..", "/", "\\");
    }

    @Test
    void detectsHeaderRowWhenPrecededByTitleRow() throws Exception {
        String detectedTemplate;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("BÁO CÁO DOANH THU ĐƠN HÀNG");

            CellStyle boldStyle = workbook.createCellStyle();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            boldStyle.setFont(boldFont);

            Row headerRow = sheet.createRow(3);
            Cell c0 = headerRow.createCell(0);
            c0.setCellValue("Mã đơn");
            c0.setCellStyle(boldStyle);

            Cell c1 = headerRow.createCell(1);
            c1.setCellValue("Khách hàng");
            c1.setCellStyle(boldStyle);

            Cell c2 = headerRow.createCell(2);
            c2.setCellValue("Tổng tiền");
            c2.setCellStyle(boldStyle);

            Row dataRow = sheet.createRow(4);
            dataRow.createCell(0).setCellValue("ORD-1");
            dataRow.createCell(1).setCellValue("Nguyen Van A");
            dataRow.createCell(2).setCellValue(100000.0);

            workbook.write(out);
            detectedTemplate = service.saveTemplate(new MockMultipartFile("file", "report.xlsx", "", out.toByteArray()));
        }

        int detected = service.detectHeaderRow(detectedTemplate);
        assertThat(detected).isEqualTo(3);
    }
}
