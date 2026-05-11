package com.cpq.varlabel;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V149 variable_label 行的轻量 POJO. 参考 V104 GlobalVariableDefinition 模式,
 * 不走 Panache 实体路线 — 服务层用 native query 加载, 保持表结构与运行期 cache 解耦.
 *
 * <p>用途: Excel 列编辑器 / 公式 [col_key] 引用时的中文友好命名 SSOT.
 * 与 V104 GlobalVariableDefinition 粒度不同: 本表 = 单列别名, V104 = 整表查表函数.</p>
 */
public class VariableLabel {

    public UUID id;
    /** 视图列路径, 例 'v_c_summary_agg.packaging_fee'. 全局唯一. */
    public String variablePath;
    /** 中文友好名, 例 '包装材料费源' */
    public String displayName;
    /** 业务分类: 成本汇总 / 费用比率 / 物料属性 / 单位标签 / 汇率 */
    public String category;
    /** DECIMAL / INTEGER / PERCENT / STRING / DATE */
    public String dataType;
    /** 单位标签, 例 '¥' / '%' / 'g' / null */
    public String unit;
    public String description;
    public String exampleValue;
    /** VIEW_COLUMN (当前唯一) / CONSTANT / DERIVED (预留) */
    public String sourceType;
    /** ACTIVE / DEPRECATED / PENDING_REVIEW */
    public String status;

    @JsonIgnore
    public OffsetDateTime createdAt;
    @JsonIgnore
    public OffsetDateTime updatedAt;
}
