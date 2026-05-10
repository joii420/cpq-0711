package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.system.entity.OperationLog;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Path("/api/cpq/operation-logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class OperationLogResource {

    @Inject
    EntityManager em;

    @GET
    public ApiResponse<PageResult<OperationLog>> list(
            @QueryParam("operationType") String operationType,
            @QueryParam("targetType") String targetType,
            @QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        List<String> conditions = new ArrayList<>();
        if (operationType != null && !operationType.isBlank()) {
            conditions.add("o.operationType = :operationType");
        }
        if (targetType != null && !targetType.isBlank()) {
            conditions.add("o.targetType = :targetType");
        }
        OffsetDateTime start = null;
        OffsetDateTime end = null;
        if (startDate != null && !startDate.isBlank()) {
            start = LocalDate.parse(startDate).atStartOfDay().atOffset(ZoneOffset.UTC);
            conditions.add("o.createdAt >= :startDate");
        }
        if (endDate != null && !endDate.isBlank()) {
            end = LocalDate.parse(endDate).plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            conditions.add("o.createdAt < :endDate");
        }

        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String hql = "FROM OperationLog o" + where + " ORDER BY o.createdAt DESC";
        String countHql = "SELECT COUNT(o) FROM OperationLog o" + where;

        TypedQuery<OperationLog> q = em.createQuery(hql, OperationLog.class);
        TypedQuery<Long> cq = em.createQuery(countHql, Long.class);

        if (operationType != null && !operationType.isBlank()) {
            q.setParameter("operationType", operationType);
            cq.setParameter("operationType", operationType);
        }
        if (targetType != null && !targetType.isBlank()) {
            q.setParameter("targetType", targetType);
            cq.setParameter("targetType", targetType);
        }
        if (start != null) {
            q.setParameter("startDate", start);
            cq.setParameter("startDate", start);
        }
        if (end != null) {
            q.setParameter("endDate", end);
            cq.setParameter("endDate", end);
        }

        long total = cq.getSingleResult();
        List<OperationLog> content = q.setFirstResult(page * size).setMaxResults(size).getResultList();

        return ApiResponse.success(new PageResult<>(content, page, size, total));
    }
}
