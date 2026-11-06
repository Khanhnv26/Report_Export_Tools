package com.report.tool.service;

import com.report.tool.dto.request.ReportConfigRequest;

import java.util.List;
import java.util.Map;

public interface ReportEngineService {

    List<Map<String, Object>> executeReport(ReportConfigRequest request);

    List<Map<String, Object>> previewReport(ReportConfigRequest request);
}
