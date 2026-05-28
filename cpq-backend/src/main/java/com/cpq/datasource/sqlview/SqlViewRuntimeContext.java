package com.cpq.datasource.sqlview;

import java.util.UUID;

/**
 * 当前请求线程的 SQL 视图运行时上下文（阶段 3 引入，阶段 4 扩展 owner 概念）。
 *
 * <p>承载必要信息以让 SqlViewExecutor + ComponentSqlViewService.lookupForResolver
 * 实现方案 §5.3 三层 fallback，以及模板独立 SQL 视图路由（方案 §6.1）。
 *
 * <p><strong>owner 互斥约束（Phase 2 更新）：</strong>
 * <ul>
 *   <li>{@code ownerType=COMPONENT} 时 {@code componentId} 必非空</li>
 *   <li>{@code ownerType=TEMPLATE} 时（V249 起，从 COSTING_TEMPLATE 改名）
 *       {@code templateId} 作为 Excel 视图渲染的模板 ID，
 *       用于查找 template_sql_view；{@code componentId} 此时无约束</li>
 *   <li>违反约束时构造 Snapshot 抛 {@link IllegalArgumentException}</li>
 * </ul>
 *
 * <p>设计参考 {@link com.cpq.formula.dataloader.PartVersionContext}（同 ThreadLocal 模式）。
 *
 * <p>调用模式（组件上下文）：
 * <pre>
 *   SqlViewRuntimeContext.set(componentId, templateId, quotationId, quotationStatus);
 *   try {
 *       // ... 调 DataLoader.loadByPath / SqlViewExecutor.execute
 *   } finally {
 *       SqlViewRuntimeContext.clear();
 *   }
 * </pre>
 *
 * <p>调用模式（模板 Excel 视图上下文，V249 新签名）：
 * <pre>
 *   Snapshot prev = SqlViewRuntimeContext.setNestedTemplate(
 *       templateId, quotationId, quotationStatus);
 *   try {
 *       // ... 调 FormulaEngine.evaluate（触发 DataLoader → SqlViewExecutor）
 *   } finally {
 *       SqlViewRuntimeContext.restore(prev);
 *   }
 * </pre>
 */
public final class SqlViewRuntimeContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private SqlViewRuntimeContext() {}

    // ─────────────────────── OwnerType 枚举 ──────────────────────────────────

    /**
     * SQL 视图的所有者类型。同一时刻线程上下文只能有一个 owner。
     */
    public enum OwnerType {
        /** 组件 SQL 视图（component_sql_view）—— 原有上下文。 */
        COMPONENT,
        /**
         * 模板 SQL 视图（template_sql_view）—— V249 起（从 COSTING_TEMPLATE 改名）。
         * 对应产品卡片模板（template 表）拥有的 SQL 视图。
         */
        TEMPLATE
    }

    // ─────────────────────── 组件上下文 API ──────────────────────────────────

    /**
     * 设置当前线程为组件 owner 上下文。向后兼容入口（阶段 3 原有签名保留不变）。
     *
     * @param componentId    当前组件 ID（本组件 {@code $name} 引用需要；null 时设 EMPTY）
     * @param templateId     当前模板 ID（PUBLISHED 模板优先读 sql_views_snapshot）
     * @param quotationId    当前报价单 ID（APPROVED/PUBLISHED/SUBMITTED 状态优先读 quotation snapshot）
     * @param quotationStatus 报价单状态
     */
    public static void set(UUID componentId, UUID templateId, UUID quotationId, String quotationStatus) {
        if (componentId == null) {
            CURRENT.set(Snapshot.EMPTY);
        } else {
            CURRENT.set(new Snapshot(OwnerType.COMPONENT, componentId,
                    templateId, quotationId, quotationStatus));
        }
    }

    /**
     * 嵌套设置（组件上下文）：先保存旧值，再覆盖。caller 在 finally 用 {@link #restore} 恢复。
     * 向后兼容入口（阶段 3 原有签名保留）。
     *
     * <p>特殊情况：{@code componentId=null} 时（如 ComponentDriverService 在 componentId 未知时调用），
     * 将设置 ownerType=COMPONENT 但 componentId=null 是非法的。
     * 为保持向后兼容，若 componentId=null 则设置 EMPTY Snapshot（不报错）。
     * 这与旧行为一致：旧版本构造时无互斥校验，null componentId 的 Snapshot 等同于"未设置上下文"。
     */
    public static Snapshot setNested(UUID componentId, UUID templateId, UUID quotationId, String quotationStatus) {
        Snapshot prev = CURRENT.get();
        if (componentId == null) {
            // 向后兼容：componentId=null 时不设置 owner 类型（等同未设置上下文）
            // SqlViewExecutor 遇到 ownerType=null 会抛 BusinessException，这是期望行为
            CURRENT.set(Snapshot.EMPTY);
        } else {
            CURRENT.set(new Snapshot(OwnerType.COMPONENT, componentId,
                    templateId, quotationId, quotationStatus));
        }
        return prev;
    }

    // ─────────────────────── 模板上下文 API（V249 新增/改名）────────────────

    /**
     * 设置当前线程为模板 owner 上下文（V249 新签名）。
     *
     * @param templateId      产品卡片模板 ID（template.id），用于查找 template_sql_view
     * @param quotationId     当前报价单 ID（可 null）
     * @param quotationStatus 报价单状态（可 null）
     */
    public static void setTemplate(UUID templateId, UUID quotationId, String quotationStatus) {
        CURRENT.set(new Snapshot(OwnerType.TEMPLATE, null,
                templateId, quotationId, quotationStatus));
    }

    /**
     * 嵌套设置（模板上下文）：先保存旧值，再覆盖。caller 在 finally 用 {@link #restore} 恢复。
     *
     * <p>典型场景：{@code FormulaEvaluateResource.evaluate} 接收 {@code templateId} 参数时调用
     * （Excel 视图渲染，ownerType=TEMPLATE，查 template_sql_view 表）。
     *
     * <p>V249 新签名；向后兼容旧调用方见 {@link #setNestedCostingTemplate}。
     */
    public static Snapshot setNestedTemplate(UUID templateId, UUID quotationId, String quotationStatus) {
        Snapshot prev = CURRENT.get();
        CURRENT.set(new Snapshot(OwnerType.TEMPLATE, null,
                templateId, quotationId, quotationStatus));
        return prev;
    }

    /**
     * 向后兼容别名：setNestedCostingTemplate → setNestedTemplate。
     *
     * <p>Phase 1 前端 agent 生成的调用代码（LinkedExcelView 的 costingTemplateId 字段）
     * 仍可能调此方法；V249 后语义改为 TEMPLATE owner（查 template_sql_view 而非 costing_template_sql_view）。
     *
     * @deprecated 新代码请直接调 {@link #setNestedTemplate}
     */
    @Deprecated
    public static Snapshot setNestedCostingTemplate(UUID templateId, UUID quotationId, String quotationStatus) {
        return setNestedTemplate(templateId, quotationId, quotationStatus);
    }

    // ─────────────────────── 通用 API ────────────────────────────────────────

    /** 恢复 setNested* 返回的旧值（finally 块必调）。 */
    public static void restore(Snapshot previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** 返回当前线程的上下文；未设置时返回空 Snapshot（所有字段 null / ownerType=null）。 */
    public static Snapshot get() {
        Snapshot s = CURRENT.get();
        return s != null ? s : Snapshot.EMPTY;
    }

    /** 清除当前线程的上下文（finally 块必调）。 */
    public static void clear() {
        CURRENT.remove();
    }

    // ─────────────────────── Snapshot ────────────────────────────────────────

    /**
     * 不可变 snapshot 对象（V249 重构：去除互斥约束中的 costingTemplateId，
     * 统一复用 templateId 字段）。
     *
     * <p>字段语义：
     * <ul>
     *   <li>{@code ownerType=COMPONENT} → {@code componentId} 为组件 ID，{@code templateId} 为产品卡片模板 ID（snapshot fallback 用）</li>
     *   <li>{@code ownerType=TEMPLATE} → {@code templateId} 为 Excel 视图渲染的模板 ID（查 template_sql_view 用），
     *       {@code componentId} 通常为 null（不强制互斥）</li>
     *   <li>{@code ownerType=null}（EMPTY）→ 所有字段为 null</li>
     * </ul>
     *
     * <p>互斥约束（精简版）：
     * <ul>
     *   <li>ownerType=COMPONENT → componentId 必非空</li>
     *   <li>ownerType=TEMPLATE → templateId 必非空；componentId 非空时抛 IllegalArgumentException（防止混用）</li>
     * </ul>
     */
    public static final class Snapshot {
        /** 空快照（未设置上下文时的默认值）。 */
        public static final Snapshot EMPTY = new Snapshot(null, null, null, null, null);

        /** owner 类型（null 表示未设置）。 */
        public final OwnerType ownerType;

        /** 组件 ID（ownerType=COMPONENT 时非空；ownerType=TEMPLATE 时通常为 null）。 */
        public final UUID componentId;

        /**
         * 模板 ID — 双重语义（通过 ownerType 区分）：
         * <ul>
         *   <li>ownerType=COMPONENT + templateId 非空 → 产品卡片模板 ID（组件 snapshot fallback 用）</li>
         *   <li>ownerType=TEMPLATE + templateId 非空 → Excel 视图渲染的模板 ID（查 template_sql_view 用）</li>
         * </ul>
         */
        public final UUID templateId;

        /** 报价单 ID（两种 owner 上下文均可设置）。 */
        public final UUID quotationId;

        /** 报价单状态（两种 owner 上下文均可设置）。 */
        public final String quotationStatus;

        public Snapshot(OwnerType ownerType, UUID componentId,
                        UUID templateId, UUID quotationId, String quotationStatus) {
            // 互斥约束校验
            if (ownerType == OwnerType.COMPONENT) {
                if (componentId == null) {
                    throw new IllegalArgumentException(
                            "SqlViewRuntimeContext: ownerType=COMPONENT 时 componentId 不能为 null");
                }
            } else if (ownerType == OwnerType.TEMPLATE) {
                if (templateId == null) {
                    throw new IllegalArgumentException(
                            "SqlViewRuntimeContext: ownerType=TEMPLATE 时 templateId 不能为 null");
                }
                if (componentId != null) {
                    throw new IllegalArgumentException(
                            "SqlViewRuntimeContext: ownerType=TEMPLATE 时 componentId 必须为 null（互斥约束）");
                }
            }
            // ownerType=null（EMPTY）: 不校验（允许两者都 null）

            this.ownerType = ownerType;
            this.componentId = componentId;
            this.templateId = templateId;
            this.quotationId = quotationId;
            this.quotationStatus = quotationStatus;
        }

        /** 是否为已发布/审批通过的报价单状态（snapshot 应优先读 quotation 行）。 */
        public boolean isQuotationFrozen() {
            return quotationStatus != null
                    && ("APPROVED".equals(quotationStatus)
                        || "PUBLISHED".equals(quotationStatus)
                        || "SUBMITTED".equals(quotationStatus));
        }
    }
}
