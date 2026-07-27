package com.cpq.quotation.service.backfill;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0721 报价数据版本升级 · B5/B6 —— 回填计划（只读中间表示，preview 与 execute 共用同一份，
 * 避免"两套收集逻辑"分叉，backtask B6 明确要求）。
 */
public final class QuoteBackfillPlan {

    public final UUID quotationId;
    public final List<GroupChange> groups = new ArrayList<>();
    /** repair-0726 B6：本单 pending 的主档料号行——供 {@code QuoteBackfillPreviewService} 计算
     *  预览 token（execute 阶段的实际转正改由 {@code MaterialMasterRepository#flipPending} 按
     *  quotationId 整单 UPDATE，不再逐行消费本字段）。字段名/形状沿用 task-0721 B9 暂存表时代的
     *  StagedRow，避免连锁改动。 */
    public List<MaterialMasterRepository.StagedRow> materialMasterPending = List.of();
    /** Q6：需要补建 material_master stub 的全新料号 → 兜底名称。 */
    public final Map<String, String> newMaterialStubs = new LinkedHashMap<>();

    public QuoteBackfillPlan(UUID quotationId) { this.quotationId = quotationId; }

    /**
     * 四条回填路径（repair-0727 技术总监裁决①：在原三条 REBUILD/FLIP/OFFLINE 基础上新增 NOOP）。
     *
     * <p>{@code NOOP}：基底来自 CURRENT 且无 CHANGE/ADD/DELETE（页签把正式行渲染出来但一字未改——
     * 报价单"从已有产品添加、未导入未编辑"是常见场景）。NOOP 组整组跳过：不进 {@link #groups}，
     * 不调 {@code VersionedV6Writer}，不计入 {@code summary.versionedGroups}，不出现在预览里。
     */
    public enum Route { REBUILD, FLIP, OFFLINE, NOOP }

    /** 单行变更（CHANGE/ADD/DELETE），供预览逐行展示 + execute 组装 newRows。 */
    public static final class RowChange {
        public String op; // CHANGE / ADD / DELETE
        public UUID v6Id; // null for ADD
        /** 物理列 → 新值（CHANGE/ADD 用于写入；DELETE 不需要）。 */
        public Map<String, Object> newValues = new LinkedHashMap<>();
        /** 物理列 → 旧值（CHANGE/DELETE 展示用；从当前 DB pending/current 行读出）。 */
        public Map<String, Object> oldValues = new LinkedHashMap<>();
        /** repair-0727 B3.2：同一 (v6Id, 列) 被多个页签同时 patch 且值不同——按 sortOrder 先到先得，
         *  本字段标注该行存在被丢弃的冲突 patch，供预览提示（api.md §1.2 {@code rows[].conflict}）。 */
        public boolean conflict;
    }

    /** 一个（表 → V6 组）的完整变更。 */
    public static final class GroupChange {
        public String table;              // 单表：写入目标表；主从：子表名（material_bom_item/element_bom_item）
        public String tabName;             // 展示用：命中的组件/页签名（多个 tab 命中同组时取第一个）
        public Map<String, Object> groupKeyAxis; // 轴列 → 值
        public Route route = Route.REBUILD;
        public boolean isGlobalShared;     // plating_scheme 全局共享标记（AC-18/R-4）
        public String versionFrom;         // 旧版本号（无则 null=首版）

        public List<String> contentColumns = List.of();
        public List<String> versionTriggerColumns; // nullable

        /** repair-0727 B3.1：基底行集（权威来源=DB，非页签），预览/断言用；REBUILD 路径同时是
         *  {@link #effectiveNewRows} 的遍历主轴。 */
        public List<Map<String, Object>> baseRows = List.of();
        /** repair-0727 B3.1：基底行来源——{@code PENDING}（本单 pending 行）/ {@code CURRENT}
         *  （该组 is_current 正式行）/ {@code NONE}（两者皆无，纯新增组）。 */
        public String baseSource = "NONE";

        /** REBUILD 路径：交给 writer 的有效行集（物理列 → 值，已含 groupKeyAxis 外的所有内容列）。
         *  repair-0727 patch 语义下 = 基底行（含未被任何页签 patch 的行）⊕ 列级 patch ⊖ 墓碑 ⊕ 新增。 */
        public final List<Map<String, Object>> effectiveNewRows = new ArrayList<>();

        /** 主从表标记（material_bom_item/element_bom_item 子表）。 */
        public boolean masterDetail;
        public String masterTable;
        public Map<String, Object> masterFixedColumns = Map.of();

        /** 预览展示：逐行 CHANGE/ADD/DELETE 分类（仅记录"有实际差异"的行——patch 命中但值未变不计入）。 */
        public final List<RowChange> rowChanges = new ArrayList<>();

        /** repair-0727 B4：物理列 → 页签列别名（去前导 {@code _}），供 {@code BackfillLabelResolver}
         *  一级中文标签查找用——来自触达本组的组件 {@code $view} 的 {@code colToBase} 反查（用户本就
         *  自己配的中文列名，比静态字典更准），先到先得（多个页签touch同表时，保留第一个）。 */
        public final Map<String, String> columnAliases = new LinkedHashMap<>();
    }
}
