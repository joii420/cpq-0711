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
}
