package com.report.tool.service.impl;


import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.SqlBuilder;
import com.report.tool.service.ReportEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor

public class ReportEngineServiceImpl implements ReportEngineService {

    private static final int BATCH_SIZE = 1000;
    private final DataSourceRegistry registry;
    private final SqlBuilder sqlBuilder;


    @Override
    public List<Map<String, Object>> executeReport(ReportConfigRequest request) {
        ReportConfigRequest.BaseTable baseTable = request.getBaseTable();

        NamedParameterJdbcTemplate baseJdbc = registry.getJdbc(baseTable.getDbId());

        Set<String> baseCols = new LinkedHashSet<>(baseTable.getColumns());

        if(request.getJoins() != null) {

            for (ReportConfigRequest.JoinTable j : request.getJoins()) {
                if(j.getSourceKey() != null && !j.getSourceKey().isBlank()) {

                }
            }
        }

        return List.of();
    }

    private void performHashJoin(List<Map<String, Object>> baseRows, ReportConfigRequest.JoinTable joinTable) {

        List<Object> distinctKeys = baseRows.stream()
                .map(r -> r.get(joinTable.getSourceKey()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if(distinctKeys.isEmpty()) return;

        NamedParameterJdbcTemplate joinJdbc = registry.getJdbc(joinTable.getDbId());
        String joinSql = sqlBuilder.buildJoinQuery(joinTable.getTableName(),joinTable.getColumns(), joinTable.getTargetKey());

        Map<String, Map<String, Object>> lookupMap = new HashMap<>();

        for (int i = 0; i < distinctKeys.size(); i += BATCH_SIZE) {


            List<Object> batch = distinctKeys.subList(i, Math.min(i + BATCH_SIZE, distinctKeys.size()));
            List<Map<String, Object>> targetRows = joinJdbc.queryForList(joinSql, Map.of("keys", batch));

            for (Map<String, Object> tr : targetRows) {
                Object keyObj = tr.get(joinTable.getTargetKey());
                if(keyObj != null) {
                    lookupMap.putIfAbsent(keyObj.toString(), tr);
                }
            }
        }

        for (Map<String, Object> baseRow : baseRows) {
            Object sourceValue = baseRow.get(joinTable.getSourceKey());

            if(sourceValue == null) {
                continue;
            }
            Map<String, Object> matched = lookupMap.get(String.valueOf(sourceValue));

            if(matched != null) {
                matched.forEach((col, val) ->
                        baseRow.put(joinTable.getTableName() + "." + col, val));
            }
        }
    }


}
