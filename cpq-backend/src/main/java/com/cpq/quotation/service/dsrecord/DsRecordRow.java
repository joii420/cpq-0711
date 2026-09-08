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

    /**
     * <b>行来源</b>（D-38 修法甲）——「这一行是主表某行的投影，还是用户自己加的」。
     *
     * <h3>为什么必须显式记，而不是从 {@link #anchorValues} 是否为空推断</h3>
     * D-36 曾用「{@code anchorValues.isEmpty() && 组已存在}」当「用户新加的一行」的判据。
     * 该谓词<b>同时命中两拨性质相反的行</b>：
     * <ul>
     *   <li>用户真新增的行（<b>必须</b>关掉粒度兜底，否则会覆盖既有行 —— D-36 的原始 bug）；</li>
     *   <li>整张单的 {@code snapshot_rows} <b>还没物化</b>时的全部 driver 行
     *       （<b>必须</b>开着粒度兜底，否则用户改过值的行永远锚不上 ⇒ 每回填一次组就变大）。</li>
     * </ul>
     * 后者是<b>新建产品行后只保存一次就提交</b>的常规形状：{@code _record} 唯一写点是
     * {@code saveDraft}，而它<b>只保留、不生成</b> {@code snapshot_rows}
     * ⇒ 首存时 driver 尚未展开，所有行都没有 driver 侧。
     * <p>A/B/C 三态实测（改值行）：守卫在 ⇒ {@code origin_id=NULL}、组 2→3 行；
     * 守卫注掉 ⇒ {@code origin_id=12483}、组 2→2 行。病灶定位到提交 {@code 1d8a1ec8}。
     */
    public Provenance provenance = Provenance.DRIVER;

    /**
     * 行来源四态。判定<b>只看结构，不看值</b>（见 {@code DsRecordProjector#project}）。
     *
     * <p>🚨 {@link #MANUAL} 的判据是 {@code row_data} 上的 {@code _origin='manual'} 标记，
     * 由前端 {@code QuotationStep2#handleAddRow}（「+ 添加行」的<b>唯一</b>入口）打上。
     * ⚠️ <b>甲的已知残留之二</b>：绕开前端、手工构造的 {@code row_data}（脚本 / 夹具 / 三方客户端）
     * 不会带这个标记 ⇒ 会被当成 driver 行 ⇒ D-36 那条保护对它不生效。
     * 🚫 判 D-36 是否回归，夹具必须<b>带 {@code _origin:'manual'}</b>，否则验的不是真实用户形状。
     */
    public enum Provenance {
        /** {@code snapshot_rows[i]} 展开出来的 driver 行 —— 主表某行的投影。 */
        DRIVER,
        /**
         * {@code snapshot_rows} <b>从未物化</b>（NULL）时，{@code row_data} 里的非手动行。
         * 语义同 {@link #DRIVER}：前端是照 driver 渲染后回传的，只是服务端还没落 snapshot。
         * <p>🔑 与 {@link #ROW_DATA_TAIL} 的区别只在 {@code snapshot_rows} 是 <b>NULL</b> 还是
         * <b>{@code []}</b>：后者代表「已物化、driver 确实返 0 行」（AP-38 形态），
         * 那时 {@code row_data} 里的行就<b>不是</b>投影，而是用户自己录的。
         */
        DRIVER_NOT_MATERIALIZED,
        /** 已物化的 driver 展开之外多出来的非手动行 ⇒ 按「用户新增」处置。 */
        ROW_DATA_TAIL,
        /** {@code _origin='manual'}：用户点「+ 添加行」加的行。 */
        MANUAL;

        /** 是否为「用户自己加的行」—— 粒度兜底对这类<b>一律关闭</b>（D-36）。 */
        public boolean userAdded() { return this == ROW_DATA_TAIL || this == MANUAL; }
    }

    /** 产出本行的页签组件 id（诊断用：同一张主表可能被 零件/外购件/BOM 树 多个页签共用）。 */
    public java.util.UUID componentId;
    /** 组件在卡片里的排序（多页签表征同一行时的稳定仲裁序）。 */
    public int sortOrder;
}
