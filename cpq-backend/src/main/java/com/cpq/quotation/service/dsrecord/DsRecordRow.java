package com.cpq.quotation.service.dsrecord;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条 {@code _record} 行 —— 报价单页签对主表某一行的<b>投影</b>（task-260907 第二段 · B-5/B-6/B-7）。
 *
 * <p>🚨 <b>「投影」三个字是本类的全部要点</b>（AP-60）：{@link #columnValues} 只含页签
 * <b>表征了的那几列</b>，主表其余列这里根本没有。回填时未出现的列必须<b>原样保留</b>，
 * 🚫 一律不许当成「用户想清空」写 NULL。
 */
public class DsRecordRow {

    /** 轴值（销售料号）。 */
    public String axisValue;
    /** A0-1 主锚：拍快照时主表那一行的 id；手工新增行 / 锚不上时为 null。 */
    public Long originId;
    /** A0-1 兜底锚：拍快照时主表那一行的 {@code row_fingerprint}；同上可为 null。 */
    public String baseRowFingerprint;
    /** 拍快照时主表该组的 {@code version_no}；主表当时无该组则 0。 */
    public int baseVersionNo;

    /** 页签表征的主表物理列 → 值（🚫 不是主表全部列）。 */
    public final Map<String, Object> columnValues = new LinkedHashMap<>();
    /** 主表对不齐的字段（自定义列 / 公式列 / 常量列 / 查名列）→ 值。D-5：不回填、不参与升版与比对。 */
    public final Map<String, Object> extendValues = new LinkedHashMap<>();

    /**
     * 🔑 <b>拍快照那一刻的 driver 原值</b>（未叠加用户编辑），只用于 {@code anchor()} 对位。
     *
     * <p>A0-1 的锚点语义是「<b>拍快照时</b>主表那一行」—— driver 行本来就是从主表读出来的，
     * 所以它才是身份键的正确来源。
     * <p>🚨 <b>不能拿 {@link #columnValues} 当身份键</b>：那是叠加过 {@code row_data} 的值，
     * 用户改任意一个数就会让「同一行」算出不同的键 ⇒ 回填变成「原行保留 + 追加一行」⇒ <b>组翻倍</b>。
     * <p>空 = 这一行只活在 {@code row_data} 里（纯 INPUT 页签行 / 手工行），没有 driver 侧。
     */
    public final Map<String, Object> anchorValues = new LinkedHashMap<>();

    /** 元素实时价快照（S-3）；仅物料BOM / 物料与元素BOM 两张有，其余为 null。 */
    public BigDecimal elementPrice;

    /** 产出本行的页签组件 id（诊断用：同一张主表可能被 零件/外购件/BOM 树 多个页签共用）。 */
    public java.util.UUID componentId;
    /** 组件在卡片里的排序（多页签表征同一行时的稳定仲裁序）。 */
    public int sortOrder;
}
