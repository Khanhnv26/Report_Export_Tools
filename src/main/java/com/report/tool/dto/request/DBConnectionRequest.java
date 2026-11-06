package com.report.tool.dto.request;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DBConnectionRequest {
    private String id;
    private String name;
    private String dbType;
    private String host;
    private int port;
    private String database;
    private String schema;
    private String username;
    @lombok.ToString.Exclude
    private String password;
}
