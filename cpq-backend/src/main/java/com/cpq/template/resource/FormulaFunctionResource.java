package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.FormulaFunctionDTO;
import com.cpq.template.dto.FormulaFunctionDTO.ExampleItem;
import com.cpq.template.dto.FormulaFunctionDTO.ParamItem;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * P0 — 公式函数清单 API.
 *
 * <pre>
 *   GET /api/cpq/formulas/functions
 * </pre>
 *
 * <p>返回 FormulaEngine（TemplateFormulaService）支持的所有函数及元数据。
 * 元数据静态硬编码，不依赖 DB。供前端公式编辑器展示函数帮助文档和自动补全候选。
 *
 * <h2>覆盖函数清单</h2>
 * 聚合: SUM_OVER / COUNT_OVER / AVG_OVER / MIN_OVER / MAX_OVER
 * 条件: IF / COALESCE / NULLIF
 * 数学: ABS
 */
@Path("/api/cpq/formulas/functions")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN", "PRICING_MANAGER"})
public class FormulaFunctionResource {

    /**
     * 返回全部支持函数的元数据列表（静态数据，无 DB 查询）。
     */
    @GET
    public ApiResponse<List<FormulaFunctionDTO>> listFunctions() {
        return ApiResponse.success(FUNCTION_CATALOG);
    }

    // ─────────────────────────────────────────────────────────────────
    // 函数元数据静态定义
    // ─────────────────────────────────────────────────────────────────

    private static final List<FormulaFunctionDTO> FUNCTION_CATALOG = buildCatalog();

    private static List<FormulaFunctionDTO> buildCatalog() {
        return List.of(

            // ── 聚合函数 ────────────────────────────────────────────────────────

            func("SUM_OVER", "聚合",
                "SUM_OVER([组件] WHERE 条件, 表达式)",
                "对组件的多行数据按条件过滤后，对每行的表达式求和。WHERE 子句可选，省略表示对所有行求和。",
                List.of(
                    ex("SUM_OVER([COMP-V5-RAW-BOM] WHERE input_qty > 0, unit_price * input_qty)",
                       "对来料BOM中 input_qty 大于 0 的行，对 unit_price × input_qty 求和"),
                    ex("SUM_OVER([COMP-V5-RAW-BOM], ABS(input_qty) / NULLIF(output_qty, 0) * unit_price)",
                       "对所有行，用绝对值投料量除以产出量再乘单价后求和（NULLIF 防除零）")
                ),
                List.of(
                    param("source",     "Component",  true,  "数据源组件，格式为 [COMP-xxx]，如 [COMP-V5-RAW-BOM]"),
                    param("where",      "Expression", false, "行过滤条件，如 input_qty > 0；省略则所有行均参与"),
                    param("expression", "Expression", true,  "对每行求值的表达式，可使用该组件的字段名")
                )
            ),

            func("COUNT_OVER", "聚合",
                "COUNT_OVER([组件] WHERE 条件, 表达式)",
                "统计组件中满足条件的行数，expression 参数通常写 1 作为计数占位。",
                List.of(
                    ex("COUNT_OVER([COMP-V5-RAW-BOM] WHERE input_qty > 0, 1)",
                       "统计来料BOM中 input_qty 大于 0 的行数")
                ),
                List.of(
                    param("source",     "Component",  true,  "数据源组件"),
                    param("where",      "Expression", false, "行过滤条件，省略表示所有行"),
                    param("expression", "Expression", true,  "计数占位，通常写 1")
                )
            ),

            func("AVG_OVER", "聚合",
                "AVG_OVER([组件] WHERE 条件, 表达式)",
                "对组件多行数据按条件过滤后，对每行表达式求算术平均值。",
                List.of(
                    ex("AVG_OVER([COMP-V5-RAW-BOM], unit_price)",
                       "对来料BOM所有行的单价求平均值")
                ),
                List.of(
                    param("source",     "Component",  true,  "数据源组件"),
                    param("where",      "Expression", false, "行过滤条件"),
                    param("expression", "Expression", true,  "对每行求值的表达式")
                )
            ),

            func("MIN_OVER", "聚合",
                "MIN_OVER([组件] WHERE 条件, 表达式)",
                "对组件多行数据按条件过滤后，返回表达式的最小值。",
                List.of(
                    ex("MIN_OVER([COMP-V5-RAW-BOM], unit_price)",
                       "取来料BOM所有行中最低单价")
                ),
                List.of(
                    param("source",     "Component",  true,  "数据源组件"),
                    param("where",      "Expression", false, "行过滤条件"),
                    param("expression", "Expression", true,  "对每行求值的表达式")
                )
            ),

            func("MAX_OVER", "聚合",
                "MAX_OVER([组件] WHERE 条件, 表达式)",
                "对组件多行数据按条件过滤后，返回表达式的最大值。",
                List.of(
                    ex("MAX_OVER([COMP-V5-RAW-BOM], unit_price)",
                       "取来料BOM所有行中最高单价")
                ),
                List.of(
                    param("source",     "Component",  true,  "数据源组件"),
                    param("where",      "Expression", false, "行过滤条件"),
                    param("expression", "Expression", true,  "对每行求值的表达式")
                )
            ),

            // ── 条件函数 ────────────────────────────────────────────────────────

            func("IF", "条件",
                "IF(condition, then_value, else_value)",
                "条件判断：满足条件返回 then_value，否则返回 else_value。支持嵌套使用。",
                List.of(
                    ex("IF([客户等级] == 'VIP', [基数] * 0.95, [基数])",
                       "VIP 客户打 95 折，其余原价"),
                    ex("IF([总重量] > 0, [总成本] / [总重量], 0)",
                       "重量大于 0 时计算单位成本，否则返回 0")
                ),
                List.of(
                    param("condition",  "Boolean",    true, "判断条件，如 x > 0、a == 'CNY'"),
                    param("then_value", "Expression", true, "条件为真时的返回值"),
                    param("else_value", "Expression", true, "条件为假时的返回值")
                )
            ),

            func("COALESCE", "条件",
                "COALESCE(value1, value2, ...)",
                "返回参数列表中第一个非 null 的值。常用于为可能为空的字段提供默认值。支持多个参数。",
                List.of(
                    ex("COALESCE([折扣率], 1.0)",
                       "若折扣率为空则使用 1.0（不打折）"),
                    ex("COALESCE([自定义单价], [标准单价], 0)",
                       "依次尝试自定义单价、标准单价，均为空时返回 0")
                ),
                List.of(
                    param("value1", "Expression", true,  "第一个候选值，若非 null 则直接返回"),
                    param("value2", "Expression", false, "第二个候选值（可继续追加更多参数）")
                )
            ),

            func("NULLIF", "条件",
                "NULLIF(value, compare_value)",
                "若 value 等于 compare_value 则返回 null，否则返回原始 value。" +
                "最常见用法：NULLIF(divisor, 0) 使除法在分母为零时返回 null 而非报错。",
                List.of(
                    ex("numerator / NULLIF(denominator, 0)",
                       "安全除法：分母为 0 时结果为 null 而不是触发错误"),
                    ex("NULLIF(discount_rate, 0)",
                       "折扣率为 0 时视为 null，配合 COALESCE 可提供默认折扣")
                ),
                List.of(
                    param("value",         "Expression", true, "原始值"),
                    param("compare_value", "Expression", true, "比较值，与 value 相等时返回 null（通常写 0）")
                )
            ),

            // ── 数学函数 ────────────────────────────────────────────────────────

            func("ABS", "数学",
                "ABS(value)",
                "返回数值的绝对值。常用于投料量等可能为负数的字段，确保参与计算的值为非负数。",
                List.of(
                    ex("ABS(input_qty)",
                       "取投料量的绝对值，适用于退料（负数）场景"),
                    ex("SUM_OVER([COMP-V5-RAW-BOM], ABS(input_qty) / NULLIF(output_qty, 0) * unit_price)",
                       "在 SUM_OVER 行表达式中对投料量取绝对值再乘单价")
                ),
                List.of(
                    param("value", "Number", true, "数值表达式，返回其绝对值")
                )
            )
        );
    }

    // ── 静态构造工具方法 ──────────────────────────────────────────────────────

    private static FormulaFunctionDTO func(String name, String category,
                                            String signature, String description,
                                            List<ExampleItem> examples,
                                            List<ParamItem> params) {
        FormulaFunctionDTO dto = new FormulaFunctionDTO();
        dto.name = name;
        dto.category = category;
        dto.signature = signature;
        dto.description = description;
        dto.examples = examples;
        dto.params = params;
        return dto;
    }

    private static ExampleItem ex(String expression, String explanation) {
        return new ExampleItem(expression, explanation);
    }

    private static ParamItem param(String name, String type, boolean required, String description) {
        return new ParamItem(name, type, required, description);
    }
}
