package com.cpq.quotation.dto.backfill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/cpq/quotations/{id}/costing-approve/preview} 响应体新增的 {@code dsBackfill} 段
 * （task-260907 第二段 · api.md §1 · B-10）。
 *
 * <h3>🚨 语义：描述「将写入什么」，不是「哪些值变了」</h3>
 * {@code AP-60} 判据四的直接产物。原实现按逐列 diff 判断有无变更，于是「组内未被页签表征的行
 * 将被删除」根本不在 diff 模型里 —— 预览显示 0 变更，执行删了 3 行。
 *
 * <h3>三条硬约束（改本类前先读 api.md §1「字段语义的三条硬约束」）</h3>
 * <ol>
 *   <li>{@link Group#untouchedRows} <b>必须出现在响应里</b>，前端必须渲染 ——
 *       财务要能看见「这一组有 7 行本次不动」；</li>
 *   <li>{@link Group#unanchoredRows} 非空时前端必须显著提示，🚫 不许折叠进「更多」；</li>
 *   <li>{@code result = "UNCHANGED"} 的组<b>仍要出现在列表里</b>（{@code patchedRows = 0}），
 *       🚫 不许过滤 —— 否则财务无法区分「这张表没变」和「这张表根本没被算进去」。</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class DsBackfillDTO {

    /** false = 本单不走 ds_ 新回填（老单 / 存量手写视图），前端不渲染该区。 */
    public boolean applicable;
    /** 恒 true —— 财务必须人工确认（D-25）。 */
    public boolean confirmRequired = true;
    public Summary summary = new Summary();
    public List<Table> tables = new ArrayList<>();
    /**
     * 🆕 D-35：本单的「{@code _record} 快照过期」警告；null = 无。
     * <p>🚨 非空时前端<b>必须显著提示</b>：此刻预览展示的内容<b>可能不是报价单的最新数据</b>
     * —— 上一次保存时 {@code _record} 写失败了（草稿本身保存成功）。
     * 🚫 不许折叠、不许静默：财务照着过期数据确认 = 按错的数据回填主表。
     */
    public RecordStale recordStale;

    /**
     * 🆕 D-33（api.md §1）：不参与基础数据升版的组件。
     * <p>🚨 非空时前端<b>必须显式告知</b>「本单有 N 个组件不参与基础数据升版」，🚫 不许静默。
     */
    public List<NonParticipating> nonParticipating = new ArrayList<>();

    /** 只落 {@code extend_column}、不回填的字段（AC-3）。 */
    public List<ExtendOnly> extendColumnOnly = new ArrayList<>();

    public static class Summary {
        /** 将被触碰的表数。 */
        public int tables;
        /** 将被触碰的「表×轴值」组数。 */
        public int axes;
        public int upgradedGroups;
        /** 判定为 UNCHANGED 的组数（一行不写）。 */
        public int unchangedGroups;
        /** 🔴 「无法对齐」的行数，&gt;0 时前端必须显著提示。 */
        public int unanchoredRows;
        /** 🆕 D-33：不参与基础数据升版的组件数，&gt;0 时前端必须显式告知。 */
        public int nonParticipatingComponents;
        /** 🆕 D-35：本单的 {@code _record} 快照是否过期（上次写失败）。true 时前端必须显著提示。 */
        public boolean recordStale;
    }

    public static class Table {
        public String sheetKey;
        /** 与 Excel sheet 名逐字相等（{@code SheetDef.sheetName}）。 */
        public String sheetName;
        public String tableName;
        public List<Group> groups = new ArrayList<>();
    }

    public static class Group {
        /** 销售料号（D-3：轴 = 报价单产品卡片的销售料号）。 */
        public String axisValue;
        /**
         * 客户编号。上游 {@code 报价侧加客户维度} 的主表 DDL 落库前，本段仍会返回本单客户的
         * {@code customer.code}；解析不出时为 {@code null}（api.md 已注明过渡期允许 null）。
         */
        public String customerNo;
        public int baseVersionNo;
        public int currentVersionNo;
        public int targetVersionNo;
        /** {@code baseVersionNo != currentVersionNo} → 走指纹重锚。 */
        public boolean crossVersion;
        /** CREATED / UPGRADED / UNCHANGED。 */
        public String result;
        /** 主表当前整组行数（= 基底行数）。 */
        public int baseRowCount;
        /** 回填后该组行数。 */
        public int resultRowCount;
        /** {@code _record} 表征并覆盖了列的行数。 */
        public int patchedRows;
        /** 🔑 页签没表征、原样保留的行数。 */
        public int untouchedRows;
        public List<UnanchoredRow> unanchoredRows = new ArrayList<>();
        public ColumnScope columnScope = new ColumnScope();
    }

    public static class UnanchoredRow {
        public Long recordId;
        /** 快照时的主表行 id（跨版后已失效）。 */
        public Long originId;
        public String baseRowFingerprint;
        public Map<String, Object> displayValues = new LinkedHashMap<>();
        /** SAME_VERSION_ORIGIN_MISS / CROSS_VERSION_FINGERPRINT_MISS / NO_ANCHOR。 */
        public String reason;
    }

    /** AP-60 列维度判据：这个页签表征了哪些列、哪些列原样保留。 */
    public static class ColumnScope {
        public List<String> patched = new ArrayList<>();
        public List<String> preserved = new ArrayList<>();
    }

    /** D-35：`_record` 快照过期警告。 */
    public static class RecordStale {
        /** 恒 true（字段存在即表示过期；前端可直接用它做条件渲染）。 */
        public boolean stale = true;
        /** {@code WRITE_FAILED}。 */
        public String reason;
        /** 异常摘要，仅供排障；🚫 不要直接当用户文案。 */
        public String detail;
        /** 标记产生时间（ISO-8601）。 */
        public String detectedAt;
    }

    /** D-33：一个不参与的组件。{@code reason} 见 {@code DsSheetBindingResolver} 的 REASON_* 常量。 */
    public static class NonParticipating {
        public String componentId;
        public String componentName;
        /** NO_BUILDER_CONFIG（实测最常见）/ NO_DRIVER_PATH / NOT_QUOTE_DIALECT / … */
        public String reason;
    }

    public static class ExtendOnly {
        public String sheetKey;
        public List<String> fields = new ArrayList<>();
    }
}
