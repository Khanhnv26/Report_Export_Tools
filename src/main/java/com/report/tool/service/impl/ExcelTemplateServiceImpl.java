package com.report.tool.service.impl;

import com.report.tool.dto.response.ExcelColumnResponse;
import com.report.tool.service.ExcelTemplateService;
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
public class ExcelTemplateServiceImpl implements ExcelTemplateService {

    private final Path uploadDir = Path.of("templates");

    public ExcelTemplateServiceImpl() throws IOException {
        if(!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    @Override
    public String saveTemplate(MultipartFile file) throws IOException {
        String safeName = UUID.randomUUID() + "_" + Objects.requireNonNull(file.getOriginalFilename());
        Path target = uploadDir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return safeName;
    }

    @Override
    public List<ExcelColumnResponse> readHeaders(String templateId, int headerRowIndex) throws Exception {
        Path path = uploadDir.resolve(templateId);
        List<ExcelColumnResponse> cols = new ArrayList<>();

        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(headerRowIndex);

            if (headerRow == null) return cols;

            DataFormatter formatter = new DataFormatter();

            for (int i = 0; i < headerRow.getLastCellNum(); i++) {

                Cell cell = headerRow.getCell(i);
                String letter = CellReference.convertNumToColString(i);
                String title = (cell != null) ? formatter.formatCellValue(cell).trim() : "";

                if (title.isEmpty()) title = "Cột " + letter;

                cols.add(ExcelColumnResponse.builder()
                        .index(i)
                        .letter(letter)
                        .title(title)
                        .build());
            }

        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi đọc header từ template", e);
        }

        return cols;
    }

    @Override
    public void fillTemplate(String templateId, int headerRowIndex, List<Map<String, Object>> rows, Map<String, String> mapping, OutputStream out) throws Exception {Path path = uploadDir.resolve(templateId);

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
            throw new RuntimeException("Lỗi khi điền dữ liệu vào template", e);
        }
    }

    private void setCellValueByType(Cell cell, Object val, CellStyle dateStyle) {
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
}
