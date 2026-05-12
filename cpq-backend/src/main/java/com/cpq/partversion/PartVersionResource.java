package com.cpq.partversion;

import com.cpq.partversion.dto.DiffSummary;
import com.cpq.partversion.dto.PartVersionLogDTO;
import com.cpq.partversion.dto.VersionDecisionDTO;
import com.cpq.partversion.dto.VersionProposeRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 料号版本管理 REST 端点 (S2).
 *
 * <p>独立端点, 不嵌入 BasicDataImportV5Resource. 业务功能不变.
 * <p>S3 阶段前端使用此接口实现"按料号卡片 + 3 选项决策"导入预览 UI.
 *
 * <p>认证: 与其他 Resource (QuotationResource 等) 一致依赖全局 auth filter;
 * 系统实际角色是 SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN,
 * 不使用 USER/ADMIN, 故不在此处加 @RolesAllowed.
 */
@Path("/api/cpq/part-version")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PartVersionResource {

    @Inject PartVersionService service;

    /** 查 (cpn, hf) 当前激活版本 + 全部历史版本日志. */
    @GET
    @Path("/{cpn}/{hf}")
    public Response getVersionInfo(@PathParam("cpn") String customerProductNo,
                                     @PathParam("hf") String hfPartNo) {
        int current = service.getCurrentVersion(customerProductNo, hfPartNo).orElse(2000);
        List<PartVersionLogDTO> history = service.listVersions(customerProductNo, hfPartNo);
        Map<String, Object> body = new HashMap<>();
        body.put("customerProductNo", customerProductNo);
        body.put("hfPartNo", hfPartNo);
        body.put("currentVersion", current);
        body.put("history", history);
        return ok(body);
    }

    /** 计算指定版本的存储指纹 (调试 / 校验用). */
    @GET
    @Path("/{cpn}/{hf}/fingerprint")
    public Response fingerprint(@PathParam("cpn") String customerProductNo,
                                  @PathParam("hf") String hfPartNo,
                                  @QueryParam("version") Integer version) {
        int v = version != null ? version
                : service.getCurrentVersion(customerProductNo, hfPartNo).orElse(2000);
        String hash = service.computeStoredFingerprint(customerProductNo, hfPartNo, v);
        Map<String, Object> body = new HashMap<>();
        body.put("customerProductNo", customerProductNo);
        body.put("hfPartNo", hfPartNo);
        body.put("version", v);
        body.put("contentHash", hash);
        return ok(body);
    }

    /** 三路判定 (S2 占位实现, S3 接入 Excel 解析后真实判定). */
    @POST
    @Path("/propose")
    public Response propose(VersionProposeRequest req) {
        if (req == null || req.customerProductNo() == null || req.hfPartNo() == null) {
            return badRequest("customerProductNo / hfPartNo 不能为空");
        }
        VersionDecisionDTO decision = service.propose(req);
        return ok(decision);
    }

    /** 应用升版决策, 写日志 + bump current_version. */
    @POST
    @Path("/{cpn}/{hf}/apply")
    public Response apply(@PathParam("cpn") String customerProductNo,
                            @PathParam("hf") String hfPartNo,
                            ApplyBumpRequest req) {
        if (req == null) return badRequest("body 不能为空");
        try {
            UUID userId = null; // S5 阶段从 SecurityContext 取
            int newVer = service.applyVersionBump(
                    customerProductNo, hfPartNo,
                    userId, req.sourceExcel(),
                    req.contentHash(), req.diffByTable());
            Map<String, Object> body = new HashMap<>();
            body.put("customerProductNo", customerProductNo);
            body.put("hfPartNo", hfPartNo);
            body.put("newVersion", newVer);
            return ok(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 切换激活版本到某历史版本 (REVERT 路径). */
    @PUT
    @Path("/{cpn}/{hf}/switch/{version}")
    public Response switchVersion(@PathParam("cpn") String customerProductNo,
                                    @PathParam("hf") String hfPartNo,
                                    @PathParam("version") int targetVersion) {
        try {
            service.switchActiveVersion(customerProductNo, hfPartNo, targetVersion);
            Map<String, Object> body = new HashMap<>();
            body.put("customerProductNo", customerProductNo);
            body.put("hfPartNo", hfPartNo);
            body.put("activeVersion", targetVersion);
            return ok(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    public record ApplyBumpRequest(
            String contentHash,
            String sourceExcel,
            Map<String, DiffSummary> diffByTable
    ) {}

    /**
     * 临时 admin: 清空报价基础数据 + 报价单 (不清模板/组件/客户/产品/核价表).
     * 仅用于测试重置场景, 后续 PR 应移除.
     */
    @POST
    @Path("/admin/wipe-basic-data")
    public Response wipeBasicData() {
        Map<String, Integer> stats = service.wipeBasicData();
        Map<String, Object> body = new HashMap<>();
        body.put("deletedByTable", stats);
        body.put("note", "清空成功. 可重新导入 Excel 测试.");
        return ok(body);
    }

    private static Response ok(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("message", "ok");
        resp.put("data", data);
        return Response.ok(resp).build();
    }

    private static Response badRequest(String msg) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 400);
        resp.put("message", msg);
        resp.put("data", null);
        return Response.status(Response.Status.BAD_REQUEST).entity(resp).build();
    }
}
