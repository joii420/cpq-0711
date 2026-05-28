package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.ComponentSqlViewDTO;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.component.service.ComponentSqlViewService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 跨组件 GLOBAL scope SQL 视图列表端点 —— 供前端 PathPicker 第 3 Tab 列出可跨引用项。
 */
@Path("/api/cpq/sql-views/global")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class GlobalSqlViewResource {

    @Inject
    ComponentSqlViewRepository repository;

    @Inject
    ComponentSqlViewService service;

    @GET
    public ApiResponse<List<ComponentSqlViewDTO>> list() {
        List<ComponentSqlView> rows = repository.listAllGlobal();
        // 一次性 batch 查所有涉及的 component.code，避免 N+1
        Map<UUID, String> codeCache = new HashMap<>();
        for (ComponentSqlView row : rows) {
            codeCache.computeIfAbsent(row.componentId, service::lookupComponentCode);
        }
        return ApiResponse.success(rows.stream()
                .map(row -> ComponentSqlViewDTO.from(row, codeCache.get(row.componentId)))
                .collect(Collectors.toList()));
    }
}
