package com.report.tool.service.impl;

import com.report.tool.dto.response.ExcelColumnResponse;
import com.report.tool.exception.TemplateNotFoundException;
import com.report.tool.exception.TemplateProcessingException;
import com.report.tool.service.ExcelTemplateService;
import com.report.tool.helper.ReportValues;
import org.springframework.beans.factory.annotation.Value;
import org.apache.poi.ss.SpreadsheetVersion;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class ExcelTemplateServiceImpl implements ExcelTemplateService {

    private final Path uploadDir;

    public ExcelTemplateServiceImpl(@Value("${report.template-directory:templates}") String templateDirectory) {
        uploadDir = Path.of(templateDirectory).toAbsolutePath().normalize();
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
        } catch (IOException e) {
            log.error("Không thể khởi tạo thư mục lưu trữ template: {}", e.getMessage());
            throw new TemplateProcessingException("Không thể khởi tạo thư mục lưu trữ template", e);
        }
    }

    @Override
    public String saveTemplate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new TemplateProcessingException("File tải lên rỗng hoặc không tồn tại!");
        }

        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "template.xlsx");
        if (!originalName.toLowerCase().endsWith(".xlsx")) {
            throw new TemplateProcessingException("Chỉ chấp nhận file định dạng Excel (.xlsx)!");
        }

        String safeName = UUID.randomUUID() + ".xlsx";
        Path target = uploadDir.resolve(safeName);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Lỗi khi lưu file template: {}", e.getMessage());
            throw new TemplateProcessingException("Không thể lưu file template lên máy chủ", e);
        }
        return safeName;
    }

    @Override
    public List<ExcelColumnResponse> readHeaders(String templateId, int headerRowIndex) {
        Path path = templatePath(templateId);
        validateHeaderRow(headerRowIndex);

        List<ExcelColumnResponse> cols = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(headerRowIndex);

            if (headerRow == null) {
                return cols;
            }

            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                String letter = CellReference.convertNumToColString(i);
                String title = (cell != null) ? formatter.formatCellValue(cell).trim() : "";

                if (title.isEmpty()) {
                    title = "Cột " + letter;
                }

                cols.add(ExcelColumnResponse.builder()
                        .index(i)
                        .letter(letter)
                        .title(title)
                        .build());
            }
        } catch (Exception e) {
            log.error("Lỗi khi đọc header từ template [{}]: {}", templateId, e.getMessage());
            throw new TemplateProcessingException("Lỗi khi đọc cấu trúc cột từ file Excel: " + e.getMessage(), e);
        }

        return cols;
    }

    @Override
    public void fillTemplate(String templateId, int headerRowIndex, List<Map<String, Object>> rows, Map<String, String> mapping, OutputStream out) {
        Path path = templatePath(templateId);
        validateHeaderRow(headerRowIndex);
        if (rows == null || (long) headerRowIndex + 1 + rows.size() > SpreadsheetVersion.EXCEL2007.getMaxRows()) {
            throw new TemplateProcessingException("Dữ liệu vượt số dòng của sheet Excel!");
        }
        if (mapping == null || mapping.values().stream().noneMatch(value -> value != null && !value.isBlank())) {
            throw new TemplateProcessingException("Cần ánh xạ ít nhất một cột Excel!");
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String letter = entry.getKey();
            if (letter == null || !letter.matches("[A-Z]{1,3}")
                    || CellReference.convertColStringToIndex(letter) >= SpreadsheetVersion.EXCEL2007.getMaxColumns()) {
                throw new TemplateProcessingException("Cột Excel không hợp lệ!");
            }
            if (entry.getValue() != null && !entry.getValue().isBlank()
                    && rows.stream().anyMatch(row -> !row.containsKey(entry.getValue()))) {
                throw new TemplateProcessingException("Ánh xạ tham chiếu cột không tồn tại: " + entry.getValue());
            }
        }

        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);

            CellStyle dateStyle = workbook.createCellStyle();
            CreationHelper createHelper = workbook.getCreationHelper();
            dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("dd/MM/yyyy HH:mm:ss"));

            int currentRowIdx = headerRowIndex + 1;
            for (Map<String, Object> dataRow : rows) {
                Row row = sheet.getRow(currentRowIdx);
                if (row == null) {
                    row = sheet.createRow(currentRowIdx);
                }
                currentRowIdx++;

                for (Map.Entry<String, String> entry : mapping.entrySet()) {
                    String colLetter = entry.getKey();
                    String fieldKey = entry.getValue();
                    if (fieldKey == null || fieldKey.isBlank()) continue;

                    int colIdx = CellReference.convertColStringToIndex(colLetter);
                    Cell cell = row.getCell(colIdx);
                    if (cell == null) {
                        cell = row.createCell(colIdx);
                    }

                    Object value = dataRow.get(fieldKey);
                    setCellValueByType(cell, value, dateStyle);
                }
            }
            workbook.write(out);
        } catch (Exception e) {
            log.error("Lỗi khi ghi dữ liệu vào template [{}]: {}", templateId, e.getMessage());
            throw new TemplateProcessingException("Lỗi khi tạo file Excel tổng hợp: " + e.getMessage(), e);
        }
    }

    private void setCellValueByType(Cell cell, Object val, CellStyle dateStyle) {
        val = ReportValues.preservePrecision(val);
        if (val == null) {
            cell.setBlank();
        } else if (val instanceof Number num) {
            cell.setCellValue(num.doubleValue());
        } else if (val instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (val instanceof Timestamp ts) {
            cell.setCellValue(ts.toLocalDateTime());
            cell.setCellStyle(dateStyle);
        } else if (val instanceof LocalDateTime ldt) {
            cell.setCellValue(ldt);
            cell.setCellStyle(dateStyle);
        } else if (val instanceof LocalDate ld) {
            cell.setCellValue(ld);
            cell.setCellStyle(dateStyle);
        } else if (val instanceof Date date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        } else {
            cell.setCellValue(val.toString());
        }
    }

    private Path templatePath(String templateId) {
        if (templateId == null || templateId.isBlank() || templateId.contains("/") || templateId.contains("\\")) {
            throw new TemplateProcessingException("Mã template không hợp lệ!");
        }
        Path path = uploadDir.resolve(templateId).normalize();
        if (!path.getParent().equals(uploadDir)) throw new TemplateProcessingException("Mã template không hợp lệ!");
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new TemplateNotFoundException(templateId);
        return path;
    }

    private void validateHeaderRow(int index) {
        if (index < 0 || index >= SpreadsheetVersion.EXCEL2007.getMaxRows()) {
            throw new TemplateProcessingException("Dòng tiêu đề Excel không hợp lệ!");
        }
    }
}
