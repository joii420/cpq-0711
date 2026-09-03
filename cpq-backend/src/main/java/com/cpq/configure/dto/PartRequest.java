package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class PartRequest {
    /** 零件品名（task-260902 语义变更：原为「配件 1」这类占位名，现为真实品名 → material_master.material_name）。 */
    public String name;

    /**
     * task-260902（api.md §1.2）：配件类型 —— {@code "PART"}（零件）| {@code "OUTSOURCED"}（外购件）。
     * 缺省视为 {@code PART}（老 payload 兼容）。
     */
    public String partType;

    /**
     * {@code 'existing'} | {@code 'new'}。
     * ⚠️ task-260902 起值域改名 {@code custom} → {@code new}，后端<b>两个都接受并等价映射</b>
     * （前端只发 {@code new}）；判定一律走 {@link #isNewMode()} / {@link #isExistingMode()}，
     * 🚫 不要再写 {@code "custom".equals(partMode)}。
     */
    public String partMode;

    /** existing 时必填。 */
    public String existingHfPartNo;

    /** task-260902：{@code partType=OUTSOURCED} 时必填（material_master 里 material_type='外购件' 的料号）。 */
    public String outsourcedPartNo;

    /** task-260902：零件规格 → {@code material_master.specification}（varchar(100)）。 */
    public String spec;

    /** task-260902：零件尺寸 → {@code material_master.dimension}（varchar(100)）。 */
    public String dimension;

    /**
     * task-260902（api.md §1.2）：零件的材质 1..N，每项带占比（Σ=100）。
     * <b>解析优先级</b>：本字段非空 → 用之；否则回落 {@link #recipeCode}/{@link #configNo}/{@link #elements}
     * 单材质（{@code ratio} 默认 100），见 {@link #effectiveMaterials()}。
     */
    public List<MaterialSelection> materials;

    /**
     * @deprecated task-260902 起由 {@link #materials} 取代（加法式扩展，本字段保留不删）。
     *             实测 {@code sel_part_signature} 0 行、{@code material_recipe_id} 1890 行全 NULL
     *             ⇒ 无存量 payload 需要兼容，回落仅为并发分支安全边。
     */
    @Deprecated
    public String recipeCode;

    /**
     * ★task-260901（api.md §2.4）：选中的<b>标准含量配置</b>编号（如 '00006-01'）。
     * 与 {@link #elements} <b>必须恰好给一个</b>，两个都给或都不给 → 400 MATERIAL_SOURCE_AMBIGUOUS。
     *
     * @deprecated task-260902 起下沉为 {@link MaterialSelection#configNo}（每材质一个）。
     */
    @Deprecated
    public String configNo;

    /**
     * custom 模式的自定义含量；给了它就要求材质 allowCustomContent=true（M-5）。
     *
     * @deprecated task-260902 起下沉为 {@link MaterialSelection#elements}（每材质一组）。
     */
    @Deprecated
    public List<ElementOverride> elements;

    /**
     * task-0712 缺口1(工序 id 契约修复, 方案A): 工序编号顺序数组(命中复用时忽略)。
     * 值 = {@code process_master.process_no}(如 "MRO-LP-0001" / 孤儿 "TP10")，
     * 不再是 {@code process}(V4) 表的 UUID —— 候选端点 / sel_template.allowed_value_key /
     * 指纹 PRC / unit_price.operation_no / quotation_line_process.process_no 全链同一标识。
     *
     * <p>⚠️ task-260902（AC-19/AC-20）：<b>数组顺序 = 工艺顺序</b>，允许重复。
     * 指纹侧排序不认顺序（换序复用同一料号），落库侧 {@code unit_price.seq_no} /
     * {@code quotation_line_process.seq_no} <b>按本数组原始下标</b> 赋值 —— 两侧有意不对称，
     * 🚫 不要为了「统一」把落库侧也排序。
     */
    public List<String> processNos;

    /** 零件<b>总重</b>（克）；三层模型下各材质克重 = 总重 × 占比（D-2）。命中复用时忽略。 */
    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal unitWeightGrams;

    /**
     * Bug B 修复: 前端在创建 lineItem 时生成的 tempId (crypto.randomUUID)。
     * existing + processNos 路径写 mat_process 时作 quotation_line_item_id，
     * 使同 hf_part_no 不同 lineItem 的工序互不干扰。
     * null = 老路径（无 lineItemId 维度），行为与 V205 之前一致。
     */
    public String quotationLineItemId;     // 前端 tempId (UUID 字符串，optional)

    /**
     * 配件组成用量（仅 COMPOSITE 子件用）。写入 material_bom_item.composition_qty。
     * 正整数，前端默认 1；null 时后端兜底为 1。SIMPLE 场景忽略。
     */
    public Integer quantity;

    // ── task-260902 派生访问器（🚫 判定一律走这里，不要散在各处写字符串比较）──

    /** {@code partMode == 'existing'}。 */
    @JsonIgnore
    public boolean isExistingMode() {
        return "existing".equals(partMode);
    }

    /** {@code partMode == 'new'}（老值 {@code 'custom'} 等价映射）。 */
    @JsonIgnore
    public boolean isNewMode() {
        return "new".equals(partMode) || "custom".equals(partMode);
    }

    /** {@code partType == 'OUTSOURCED'}；缺省视为 PART。 */
    @JsonIgnore
    public boolean isOutsourced() {
        return "OUTSOURCED".equals(partType);
    }

    /**
     * 三层模型的材质列表 —— {@link #materials} 优先；为空时把老的单值字段
     * 回落成一项（{@code ratio=100}）并<b>就地物化到 {@link #materials}</b>。
     *
     * <p>就地物化是刻意的：{@code prepareMaterialSelection} 会把 configNo 展开成 elements
     * 并置 {@code materialResolved}，若每次调用都新建一个回落对象，
     * {@code lookupFingerprint} 与 {@code configure} 之间的解析结果会丢失、幂等标志也失效。
     */
    @JsonIgnore
    public List<MaterialSelection> effectiveMaterials() {
        if (materials == null || materials.isEmpty()) {
            List<MaterialSelection> fallback = new ArrayList<>(1);
            if (recipeCode != null && !recipeCode.isBlank()) {
                fallback.add(new MaterialSelection(recipeCode, configNo, new BigDecimal("100"), elements));
            }
            materials = fallback;
        }
        return materials;
    }
}
