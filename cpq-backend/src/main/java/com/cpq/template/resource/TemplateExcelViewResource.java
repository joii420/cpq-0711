package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.importexcel.service.CustomerExcelTemplateService;
import com.cpq.quotation.service.ExcelViewService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Excel View configuration endpoints for Templates (v2).
 * GET/PUT /api/cpq/templates/{id}/excel-view-config
 */
@Path("/api/cpq/templates/{id}/excel-view-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class TemplateExcelViewResource {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    CustomerExcelTemplateService customerExcelTemplateService;

    /**
     * GET /api/cpq/templates/{id}/excel-view-config
     * Returns the excel_view_config as a parsed JSON array (not a stringified value).
     */
    @GET
    public ApiResponse<Object> getConfig(@PathParam("id") UUID id) {
        String json = excelViewService.getExcelViewConfig(id);
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    json == null || json.isBlank() ? "[]" : json, Object.class);
            return ApiResponse.success(parsed);
        } catch (Exception e) {
            // Fall back to raw string if not valid JSON (defensive — should not happen)
            return ApiResponse.success(json);
        }
    }

    /**
     * PUT /api/cpq/templates/{id}/excel-view-config
     * Saves the excel_view_config on a DRAFT template.
     * Body: raw JSON array of column definitions.
     * Example column:
     * { "col_key": "A", "label": "Schneider Part No", "source_type": "PRODUCT_ATTRIBUTE", "field_key": "schneiderPartNo" }
     * { "col_key": "B", "label": "Ag%", "source_type": "COMPONENT_FIELD", "field_key": "agPercent" }
     * { "col_key": "C", "label": "Calc", "source_type": "EXCEL_FORMULA", "formula": "=A1*B1" }
     * { "col_key": "D", "label": "Fixed", "source_type": "FIXED_VALUE", "fixed_value": "1.0" }
     */
    @PUT
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<String> saveConfig(@PathParam("id") UUID id, String body) {
        return ApiResponse.success(excelViewService.saveExcelViewConfig(id, body));
    }

    /**
     * POST /api/cpq/templates/{id}/excel-view-config/dry-run-tab-formula
     * 试算：给样本 lineItem + TAB_JOIN_FORMULA 列配置 → 返回单值。
     * 请求体：{"lineItemId": "uuid", "column": {...col定义...}, "cardValuesJson": "可选"}
     * 响应：{"value": BigDecimal|null, "errors": [...]}
     */
    @POST
    @Path("/dry-run-tab-formula")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> dryRunTabFormula(
            @PathParam("id") UUID templateId,
            Map<String, Object> body) {
        if (body == null) throw new BusinessException(400, "请求体不能为空");
        Object liIdObj = body.get("lineItemId");
        if (liIdObj == null) throw new BusinessException(400, "lineItemId is required");
        Object colObj = body.get("column");
        if (colObj == null) throw new BusinessException(400, "column is required");
        if (!(colObj instanceof Map)) throw new BusinessException(400, "column 必须是对象");
        @SuppressWarnings("unchecked")
        Map<String, Object> column = (Map<String, Object>) colObj;
        String cardValuesJson = body.get("cardValuesJson") != null
            ? body.get("cardValuesJson").toString() : null;
        UUID lineItemId;
        try {
            lineItemId = UUID.fromString(liIdObj.toString());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "lineItemId 格式非法: " + liIdObj);
        }
        return ApiResponse.success(excelViewService.dryRunTabFormula(lineItemId, column, cardValuesJson));
    }

    /**
     * GET /api/cpq/templates/{id}/excel-view-config/tab-defs
     * 模板页签定义：返回各组件的 alias/tabKey/rowKeyFields/detailFields/subtotalCols。
     * 供前端 TAB_JOIN_FORMULA 构建器初始化页签选择列表。
     */
    @GET
    @Path("/tab-defs")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<Map<String, Object>>> tabDefs(@PathParam("id") UUID templateId) {
        return ApiResponse.success(excelViewService.tabDefsOfTemplate(templateId));
    }

    /**
     * GET /api/cpq/templates/{id}/excel-view-config/sample-cards
     * 样本卡片：引用该模板的报价行（最多 50 条），供前端选样本进行试算。
     * 响应：[{quotationId, quotationNo, lineItemId, cardName}]
     */
    @GET
    @Path("/sample-cards")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<Map<String, Object>>> sampleCards(@PathParam("id") UUID templateId) {
        return ApiResponse.success(excelViewService.sampleCardsOfTemplate(templateId));
    }

    /**
     * POST /api/cpq/templates/{id}/excel-view-config/parse-header
     * Upload an Excel file and return its header row column names.
     * Used by the v3 import UI to map Excel columns to template columns.
     *
     * @param sheetIndex     1-based sheet index (default 1)
     * @param headerRowIndex 1-based header row number (default 2)
     */
    @POST
    @Path("/parse-header")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<String>> parseHeader(
            @PathParam("id") UUID templateId,
            @RestForm("file") FileUpload file,
            @RestForm("sheetIndex") @DefaultValue("1") int sheetIndex,
            @RestForm("headerRowIndex") @DefaultValue("2") int headerRowIndex) {
        if (file == null) throw new BusinessException(400, "file is required");
        try (InputStream is = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            List<String> headers = customerExcelTemplateService.parseExcelHeaders(is, sheetIndex, headerRowIndex);
            return ApiResponse.success(headers);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to parse Excel headers: " + e.getMessage());
        }
    }
}
