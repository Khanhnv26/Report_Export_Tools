package com.report.tool.service;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.OutputColumn;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.service.ReportOutputService.PreparedReport;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportWorkbookService {
    private final ExcelTemplateService templates;

    public void write(ReportConfigRequest config, PreparedReport report, OutputStream out) throws IOException {
        Workbook workbook = report.template() ? templates.openTemplate(config.getTemplateId()) : new SXSSFWorkbook(100);
        try (workbook) {
            Sheet sheet = report.template() ? workbook.getSheetAt(0) : workbook.createSheet("Bao cao");
            int header = report.template() ? config.getHeaderRow() : 0;
            if (header < 0 || (long) header + report.rows().size() + 1 > 1048576) throw new InvalidReportConfigException("Số dòng vượt giới hạn sheet.");
            Map<String, CellStyle> styles = new HashMap<>();
            if (!report.template()) {
                Row title = sheet.createRow(0);
                CellStyle titleStyle = workbook.createCellStyle();
                Font font = workbook.createFont(); font.setBold(true); titleStyle.setFont(font);
                titleStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                for (int c = 0; c < report.columns().size(); c++) {
                    title.createCell(c).setCellValue(report.columns().get(c).getTitle());
                    title.getCell(c).setCellStyle(titleStyle);
                    sheet.setColumnWidth(c, Math.min(60, Math.max(18, report.columns().get(c).getTitle().length() + 4)) * 256);
                }
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(new CellRangeAddress(0, report.rows().size(), 0, report.columns().size() - 1));
            }
            List<CellStyle> defaults = new ArrayList<>();
            Row sample = sheet.getRow(header + 1);
            for (int c = 0; c < report.columns().size(); c++) {
                int index = report.template() ? CellReference.convertColStringToIndex(report.columns().get(c).getLetter()) : c;
                defaults.add(sample != null && sample.getCell(index) != null ? sample.getCell(index).getCellStyle() : workbook.getCellStyleAt(0));
            }
            for (int r = 0; r < report.rows().size(); r++) {
                ReportCancellation.check();
                Row row = sheet.getRow(header + r + 1);
                if (row == null) row = sheet.createRow(header + r + 1);
                for (int c = 0; c < report.columns().size(); c++) {
                    OutputColumn column = report.columns().get(c);
                    int index = report.template() ? CellReference.convertColStringToIndex(column.getLetter()) : c;
                    Cell cell = row.getCell(index);
                    boolean existed = cell != null;
                    if (cell == null) cell = row.createCell(index);
                    if (!existed && defaults.size() > c) cell.setCellStyle(defaults.get(c));
                    setCell(cell, report.rows().get(r).get(c), column, styles);
                }
            }
            workbook.write(out);
        }
    }

    public List<List<String>> preview(ReportConfigRequest config, PreparedReport report) {
        Workbook workbook = report.template() ? templates.openTemplate(config.getTemplateId()) : new XSSFWorkbook();
        try (workbook) {
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
            Sheet sheet = report.template() ? workbook.getSheetAt(0) : workbook.createSheet();
            int header = report.template() ? config.getHeaderRow() : -1;
            List<CellStyle> defaults = new ArrayList<>();
            Row sample = sheet.getRow(header + 1);
            for (int c = 0; c < report.columns().size(); c++) {
                int index = report.template() ? CellReference.convertColStringToIndex(report.columns().get(c).getLetter()) : c;
                defaults.add(sample != null && sample.getCell(index) != null ? sample.getCell(index).getCellStyle() : workbook.getCellStyleAt(0));
            }
            Map<String, CellStyle> styles = new HashMap<>();
            List<List<String>> result = new ArrayList<>();
            for (int r = 0; r < Math.min(20, report.rows().size()); r++) {
                Row row = sheet.getRow(header + r + 1);
                if (row == null) row = sheet.createRow(header + r + 1);
                List<String> textRow = new ArrayList<>();
                for (int c = 0; c < report.columns().size(); c++) {
                    OutputColumn column = report.columns().get(c);
                    int index = report.template() ? CellReference.convertColStringToIndex(column.getLetter()) : c;
                    Cell cell = row.getCell(index);
                    boolean existed = cell != null;
                    if (cell == null) cell = row.createCell(index);
                    if (!existed && defaults.size() > c) cell.setCellStyle(defaults.get(c));
                    setCell(cell, report.rows().get(r).get(c), column, styles);
                    textRow.add(formatter.formatCellValue(cell));
                }
                result.add(textRow);
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Không tạo được xem trước bảng tính.", ex);
        }
    }

    private void setCell(Cell cell, Object value, OutputColumn column, Map<String, CellStyle> styles) {
        if (value == null) cell.setBlank();
        else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof Boolean bool) cell.setCellValue(bool);
        else if (value instanceof Timestamp timestamp) cell.setCellValue(timestamp.toLocalDateTime());
        else if (value instanceof LocalDateTime date) cell.setCellValue(date);
        else if (value instanceof LocalDate date) cell.setCellValue(date);
        else if (value instanceof Date date) cell.setCellValue(date);
        else cell.setCellValue(value.toString());
        String resolvedFormat = null;
        if (column.getFormat() != null) {
            resolvedFormat = switch (column.getFormat()) {
                case "INTEGER" -> "#,##0";
                case "DECIMAL", "MONEY" -> "#,##0.00";
                case "DATE" -> "dd/mm/yyyy";
                case "DATETIME" -> "dd/mm/yyyy hh:mm:ss";
                default -> null;
            };
        }
        if (resolvedFormat == null) {
            resolvedFormat = value instanceof LocalDate ? "dd/mm/yyyy" : (value instanceof Date || value instanceof LocalDateTime)
                    ? "dd/mm/yyyy hh:mm:ss" : null;
        }
        if (resolvedFormat != null) {
            final String format = resolvedFormat;
            String key = cell.getCellStyle().getIndex() + ":" + format;
            cell.setCellStyle(styles.computeIfAbsent(key, ignored -> {
                CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
                style.cloneStyleFrom(cell.getCellStyle());
                style.setDataFormat(cell.getSheet().getWorkbook().createDataFormat().getFormat(format));
                return style;
            }));
        }
    }
}
