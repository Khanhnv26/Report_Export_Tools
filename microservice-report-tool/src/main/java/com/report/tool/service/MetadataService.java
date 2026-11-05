package com.report.tool.service;


import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;

import java.sql.SQLException;
import java.util.List;

public interface MetadataService {
    DatabaseResponse addDataBase(DatabaseResponse databaseResponse) throws Exception;
    void removeDataBase(String databaseId);
    List<DatabaseResponse> getAllDataBases();
    List<String> getTables(String dbId) throws SQLException;
    List<ColumnResponse> getColumns(String dbId, String tableName) throws SQLException;
}
