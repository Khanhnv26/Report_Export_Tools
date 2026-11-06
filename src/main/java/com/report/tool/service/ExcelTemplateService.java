package com.report.tool.service;

import com.report.tool.dto.response.ExcelColumnResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public interface ExcelTemplateService {

    String saveTemplate(MultipartFile file);

    List<ExcelColumnResponse> readHeaders(String templateId, int headerRowIndex);

    void fillTemplate(String templateId,
                      int headerRowIndex,
                      List<Map<String, Object>> rows,
                      Map<String, String> mapping,
                      OutputStream out);
}
