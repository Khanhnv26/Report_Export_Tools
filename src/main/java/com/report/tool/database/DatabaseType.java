package com.report.tool.database;

public record DatabaseType(String id, String name, int defaultPort, String driverClassName,
                           String databaseLabel, boolean supportsSchema) { }
