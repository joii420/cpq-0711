package com.cpq.component.dto;

import java.util.Map;
import java.util.UUID;

/**
 * L1: 运行时上下文变量字典 (2026-05-21, 统一智能视图路径方案 §13.2.1).
 *
 * <p>作为 BNF path 谓词显式化 + 公式 token 展开的统一上下文传递容器。
 * 后端在 driver expand 时组装此对象，传入 ContextInterpolator 对 path 内的
 * {@code {lineItem.partNo}} / {@code {quotation.customerId}} 等占位符做字符串替换。
 *
 * <p>向后兼容：所有字段均可为 null（占位符不存在时 interpolator 保留原字符串或返 null）。
 */
public class RuntimeContext {

    /** 报价单行项目上下文：料号 / compositeType / id */
    public LineItemContext lineItem;

    /** 报价单级上下文：报价单 id / 客户 id */
    public QuotationContext quotation;

    /** 当前登录用户上下文（可选，占位符预留，具体能力 L5 实现） */
    public UserContext user;

    /**
     * 行级字段 Map（用于父子表 JOIN 场景，例如元素含量 Tab 行的 element_name 传递给单价查询）.
     * key = 字段名（中文或英文），value = 当前行的字段值.
     */
    public Map<String, Object> row;

    /**
     * 全局变量映射（可选，复用现有 GlobalVariable 表，key = 变量 code，value = 变量值）.
     */
    public Map<String, Object> global;

    // ─────────────────────────────────────────────────────────────────────────
    // 内部 DTO 类
    // ─────────────────────────────────────────────────────────────────────────

    public static class LineItemContext {
        /** 料号（卡片 hf_part_no）。对应 ImplicitJoinRewriter 注入的 hf_part_no 谓词。 */
        public String partNo;

        /** 产品形态：SIMPLE | COMPOSITE | PART */
        public String compositeType;

        /** 报价行 UUID（Bug B lineItemId 隔离用） */
        public UUID id;

        /** 前端分配的临时 UUID（configure 事务提交前使用） */
        public UUID tempId;

        public LineItemContext() {}

        public LineItemContext(String partNo, String compositeType, UUID id) {
            this.partNo = partNo;
            this.compositeType = compositeType;
            this.id = id;
        }
    }

    public static class QuotationContext {
        /** 报价单 UUID */
        public UUID id;

        /** 客户 UUID。对应 customer_id 谓词注入。 */
        public UUID customerId;

        /** 客户分类（可选，L4 visibleWhen 表达式用） */
        public String customerCategory;

        /** 产品分类 UUID */
        public UUID productCategoryId;

        public QuotationContext() {}

        public QuotationContext(UUID id, UUID customerId) {
            this.id = id;
            this.customerId = customerId;
        }
    }

    public static class UserContext {
        /** 用户 UUID */
        public UUID id;

        /** 用户角色（L4 Tab 显隐表达式用） */
        public String role;

        public UserContext() {}

        public UserContext(UUID id, String role) {
            this.id = id;
            this.role = role;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工厂方法
    // ─────────────────────────────────────────────────────────────────────────

    /** 从最常用的 (partNo, customerId) 快速构造最小 RuntimeContext */
    public static RuntimeContext of(String partNo, UUID customerId) {
        RuntimeContext ctx = new RuntimeContext();
        ctx.lineItem = new LineItemContext(partNo, null, null);
        ctx.quotation = new QuotationContext(null, customerId);
        return ctx;
    }

    /** 包含 lineItemId 的完整构造（Bug B 场景） */
    public static RuntimeContext of(String partNo, UUID customerId, UUID lineItemId) {
        RuntimeContext ctx = of(partNo, customerId);
        ctx.lineItem.id = lineItemId;
        return ctx;
    }
}
