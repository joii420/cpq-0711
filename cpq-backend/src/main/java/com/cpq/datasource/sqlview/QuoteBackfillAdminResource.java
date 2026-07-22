package com.cpq.datasource.sqlview;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task-0721 报价数据版本升级 · B3 —— 启动期视图校验结果查询端点（api.md §4，诊断用）。
 *
 * <p>启动时若有 failed&gt;0，应用直接启动失败（fail-fast，见 {@link QuoteViewValidationService}）；
 * 本端点供运维复查"已成功启动实例"的最近一次校验快照（理论上恒为全 ok，因为 failed&gt;0 时进程
 * 根本起不来——保留端点是为了让运维能确认"校验确实跑过 + 覆盖范围有多少个视图"，而非盲目信任）。
 */
@Path("/api/cpq/admin/quote-backfill")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class QuoteBackfillAdminResource {

    @Inject QuoteViewValidationService validationService;

    @GET
    @Path("/view-validation")
    public ApiResponse<Map<String, Object>> viewValidation() {
        QuoteViewValidationService.Snapshot s = validationService.getLastSnapshot();
        if (s == null) {
            // 理论不可达（onStartup 必先于任何 HTTP 请求跑完），防御性兜底：现跑一次。
            s = validationService.runValidation();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", s.checkedAt);
        out.put("total", s.total);
        out.put("ok", s.ok);
        out.put("failed", s.failed);
        List<Map<String, String>> failures = s.failures.stream().map(f -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("component", f.component);
            m.put("view", f.view);
            m.put("reason", f.reason);
            return m;
        }).toList();
        out.put("failures", failures);
        return ApiResponse.success(out);
    }
}
