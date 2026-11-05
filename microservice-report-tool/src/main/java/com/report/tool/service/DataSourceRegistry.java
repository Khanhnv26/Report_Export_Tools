package com.report.tool.service;

import com.report.tool.dto.request.DBConnectionRequest;

public interface DataSourceRegistry {

    void connectInternal(DBConnectionRequest request, boolean persist) throws Exception;

}
