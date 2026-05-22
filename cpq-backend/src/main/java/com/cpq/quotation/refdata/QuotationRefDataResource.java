package com.cpq.quotation.refdata;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.cpq.quotation.entity.Quotation;
import com.cpq.template.entity.TemplateGlobalVariableBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * V212: 报价单引用数据端点 (ADR-002 §4.1 端点 3、4).
 *
 * <p>端点 3: GET /api/cpq/quotations/{qid}/ref-data
 *   - 仅 DRAFT 报价单可用 (非 DRAFT 抛 400 QUOTATION_NOT_DRAFT)
 *   - 按 customer_template_id 拉绑定, 每个调 GlobalVariableDataLoader 实时返回
 *
 * <p>端点 4: GET /api/cpq/quotations/{qid}/ref-data/snapshot
 *   - 任意非 DRAFT 状态可用 (DRAFT 抛 400 QUOTATION_IS_DRAFT)
 *   - 直接读 quotation.bound_global_variables_snapshot JSONB 反序列化返回
 */
@Path("/api/cpq/quotations/{qid}/ref-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class QuotationRefDataResource {

    private static final Logger LOG = Logger.getLogger(QuotationRefDataResource.class);

    @Inject
    ObjectMapper mapper; // Quarkus-managed: 已注册 JavaTimeModule，正确处理 OffsetDateTime

    @Inject
    EntityManager em;

    @Inject
    GlobalVariableDataLoader globalVariableDataLoader;

    @Inject
    GlobalVariableService globalVariableService;

    /**
     * 实时拉取引用数据 (仅 DRAFT 报价单).
     *
     * <p>按 quotation.customer_template_id 查绑定列表,
     * 对每个绑定调 GlobalVariableDataLoader.loadAllRows, 组装 BoundGvViewDTO 列表返回.
     *
     * @param qid 报价单 ID
     * @return 引用数据列表 (按 display_order 升序)
     */
    @GET
    public ApiResponse<List<BoundGvViewDTO>> getRealTimeRefData(@PathParam("qid") UUID qid) {
        Quotation quotation = Quotation.findById(qid);
        if (quotation == null) {
            throw new BusinessException(404, "QUOTATION_NOT_FOUND: 报价单不存在: " + qid);
        }

        // 仅 DRAFT 允许实时拉取
        if (!"DRAFT".equals(quotation.status)) {
            throw new BusinessException(400,
                    "QUOTATION_NOT_DRAFT: 非 DRAFT 状态的报价单请使用 /ref-data/snapshot 端点");
        }

        UUID templateId = quotation.customerTemplateId;
        if (templateId == null) {
            return ApiResponse.success(Collections.emptyList());
        }

        List<TemplateGlobalVariableBinding> bindings = TemplateGlobalVariableBinding.list(
                "templateId = ?1 ORDER BY displayOrder ASC", templateId);

        if (bindings.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }

        OffsetDateTime fetchedAt = OffsetDateTime.now();
        List<BoundGvViewDTO> result = new ArrayList<>(bindings.size());

        for (TemplateGlobalVariableBinding binding : bindings) {
            try {
                GlobalVariableDefinition def = globalVariableService.getByCode(binding.globalVariableCode)
                        .orElse(null);
                if (def == null) {
                    LOG.warnf("[QuotationRefDataResource] GV code=%s not found, skip", binding.globalVariableCode);
                    continue;
                }

                BoundGvViewDTO dto = new BoundGvViewDTO();
                dto.code = def.code;
                dto.name = def.name;
                dto.varType = def.varType;
                dto.unit = def.unit;
                dto.displayOrder = binding.displayOrder;
                dto.fetchedAt = fetchedAt;
                dto.columns = globalVariableDataLoader.buildColumns(def);
                dto.rows = globalVariableDataLoader.loadAllRows(binding.globalVariableCode);
                result.add(dto);
            } catch (Exception e) {
                LOG.warnf("[QuotationRefDataResource] loadAllRows failed for code=%s: %s",
                        binding.globalVariableCode, e.getMessage());
            }
        }

        return ApiResponse.success(result);
    }

    /**
     * 读取提交时的快照 (任意非 DRAFT 报价单).
     *
     * <p>直接从 quotation.bound_global_variables_snapshot JSONB 反序列化返回.
     * NULL 或 '[]' 时返空数组.
     *
     * @param qid 报价单 ID
     * @return 快照列表 (按 displayOrder 升序)
     */
    @GET
    @Path("/snapshot")
    public ApiResponse<List<BoundGvSnapshotItem>> getSnapshot(@PathParam("qid") UUID qid) {
        Quotation quotation = Quotation.findById(qid);
        if (quotation == null) {
            throw new BusinessException(404, "QUOTATION_NOT_FOUND: 报价单不存在: " + qid);
        }

        // DRAFT 没有快照, 阻止调用
        if ("DRAFT".equals(quotation.status)) {
            throw new BusinessException(400,
                    "QUOTATION_IS_DRAFT: DRAFT 报价单尚未提交, 没有快照数据, 请使用 /ref-data 端点");
        }

        // 读 bound_global_variables_snapshot JSONB
        String snapshotJson = getBoundGvSnapshot(qid);
        if (snapshotJson == null || snapshotJson.isBlank() || "[]".equals(snapshotJson.trim())) {
            return ApiResponse.success(Collections.emptyList());
        }

        try {
            List<BoundGvSnapshotItem> items = mapper.readValue(snapshotJson,
                    new TypeReference<List<BoundGvSnapshotItem>>() {});
            return ApiResponse.success(items);
        } catch (Exception e) {
            LOG.warnf("[QuotationRefDataResource] snapshot deserialize failed for qid=%s: %s", qid, e.getMessage());
            return ApiResponse.success(Collections.emptyList());
        }
    }

    /**
     * 从 DB 读取 bound_global_variables_snapshot 列 (原始 JSON 字符串).
     */
    @SuppressWarnings("unchecked")
    private String getBoundGvSnapshot(UUID qid) {
        try {
            List<Object> rows = em.createNativeQuery(
                    "SELECT bound_global_variables_snapshot::text FROM quotation WHERE id = :qid")
                    .setParameter("qid", qid)
                    .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            return rows.get(0).toString();
        } catch (Exception e) {
            LOG.warnf("[QuotationRefDataResource] getBoundGvSnapshot failed for qid=%s: %s", qid, e.getMessage());
            return null;
        }
    }
}
