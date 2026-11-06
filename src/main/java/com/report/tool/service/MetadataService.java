package com.report.tool.service;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.dto.response.ColumnResponse;
import com.report.tool.dto.response.DatabaseResponse;

import java.util.List;

public interface MetadataService {

    DatabaseResponse addDataBase(DBConnectionRequest request);

    void removeDataBase(String databaseId);

    List<DatabaseResponse> getAllDataBases();

    List<String> getTables(String dbId);

    List<ColumnResponse> getColumns(String dbId, String tableName);
}
