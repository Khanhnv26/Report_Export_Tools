package com.report.tool.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class ReportConfigRequest {

    private String templateId;

    @Builder.Default
    private int headerRow = 0;

    private BaseTable baseTable;

    @Builder.Default
    private List<JoinTable> joins = new ArrayList<>();

    @Builder.Default
    private Map<String, String> mapping = new LinkedHashMap<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BaseTable {
        private String dbId;
        private String tableName;
        private String alias;

        @Builder.Default
        private List<String> columns = new ArrayList<>();

        private String filterColumn;
        private String fromDate;
        private String toDate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class JoinTable {
        private String dbId;
        private String tableName;
        private String alias;

        @Builder.Default
        private List<String> columns = new ArrayList<>();

        private String sourceKey;
        private String targetKey;
    }
}
