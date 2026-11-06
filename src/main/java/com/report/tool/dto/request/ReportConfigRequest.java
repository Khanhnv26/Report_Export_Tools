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
    private String outputMode;
    @Builder.Default
    private List<OutputColumn> outputColumns = new ArrayList<>();
    @Builder.Default
    private List<Filter> filters = new ArrayList<>();
    private String sortField;
    @Builder.Default
    private String sortDirection = "ASC";

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class OutputColumn {
        private String field;
        private String title;
        private String letter;
        @Builder.Default private String type = "AUTO";
        @Builder.Default private String format = "GENERAL";
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Filter {
        private String field;
        private String operator;
        private String value;
    }

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
