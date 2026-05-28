package com.cpq.formula.dto;

import java.util.Map;
import java.util.UUID;

/**
 * 公式求值请求(对外 REST)。
 *
 * <p>使用场景:
 * <ul>
 *   <li>组件管理配置时:校验/试算公式语法 — 留 customerId/partNo 为空,只验路径解析正确</li>
 *   <li>报价单运行时:报价单当前行有 customerId + partNo 上下文,前端调此端点求值含 BNF 路径的公式</li>
 *   <li>Excel 视图渲染（Phase 1 新增）:传 costingTemplateId + quotationId + quotationStatus，
 *       后端在 FormulaEvaluateResource 入口设置 SqlViewRuntimeContext.ownerType=COSTING_TEMPLATE</li>
 * </ul>
 */
public class EvaluateRequest {

    /** 公式表达式(支持 {表[谓词].字段} BNF 路径 + [字段名] + 运算 + 函数);空值由 endpoint 业务层返回 PARSE_ERROR */
    public String expression;

    /** 当前客户 UUID(查 mat_process / mat_fee / plating_fee 等客户级表时自动注入到 customer_id 谓词) */
    public UUID customerId;

    /** 当前料号(自动注入到 hf_part_no / part_no 谓词) */
    public String partNo;

    /** 行级变量(对应 row_data 中的 [字段名] 引用) */
    public Map<String, Object> bindings;

    /**
     * Y1.5 行驱动行(可选)。非空时,字段路径求值会自动把这里的 K-V 作为
     * 隐式 AND 谓词注入到字段路径首段(目标表存在该列时才注入)。
     */
    public Map<String, Object> driverRow;

    // ────── Phase 2 (V249)：模板 SQL 视图上下文（从 costingTemplateId 改名为 templateId）────────

    /**
     * 产品卡片模板 UUID（可选）。
     * 非空时 FormulaEvaluateResource 在求值前设置 SqlViewRuntimeContext.ownerType=TEMPLATE，
     * 让含 {@code $view.col} 路径的公式查 template_sql_view 表（V249 起）。
     *
     * <p>与 customerId / partNo 一起用于缓存 key（防跨模板串混）。
     *
     * <p>V249 改名说明：原字段 {@code costingTemplateId} 已废弃，统一改为 {@code templateId}，
     * 前端调用方应更新字段名；后端同时接受 {@code costingTemplateId} 作为向后兼容别名
     * （通过前端旧代码自动 fallback：若 templateId=null 且 costingTemplateId 非 null，
     * 则 FormulaEvaluateResource 会取 costingTemplateId）。
     */
    public UUID templateId;

    /**
     * 向后兼容别名（Phase 1 遗留字段名）。
     * 新前端代码应使用 {@link #templateId}；
     * 若 {@code templateId} 为 null 但 {@code costingTemplateId} 非 null，
     * FormulaEvaluateResource 会自动取 costingTemplateId 作为 templateId 使用。
     *
     * @deprecated 请改用 {@link #templateId}
     */
    @Deprecated
    public UUID costingTemplateId;

    /**
     * 当前报价单 ID（可选，配合 templateId 使用）。
     * 传入后 SqlViewRuntimeContext 可做报价单状态冻结判断。
     */
    public UUID quotationId;

    /**
     * 当前报价单状态（可选，配合 templateId 使用）。
     * 如 DRAFT / SUBMITTED / APPROVED / PUBLISHED。
     */
    public String quotationStatus;
}
