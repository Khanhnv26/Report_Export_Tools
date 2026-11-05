package com.report.tool.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class DatabaseResponse {
    private String id;
    private String name;
    private String dbType;
    private String host;
    private int port;
    private String databaseName;
}
