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
    /** B9：本单暂存的主档变更（原样透传给 promote，不在此重复解析）。 */
    public List<MaterialMasterRepository.StagedRow> materialMasterStaging = List.of();
    /** Q6：需要补建 material_master stub 的全新料号 → 兜底名称。 */
    public final Map<String, String> newMaterialStubs = new LinkedHashMap<>();

    public QuoteBackfillPlan(UUID quotationId) { this.quotationId = quotationId; }

    /** 三条回填路径（backtask B5.2「三条回填路径」）。 */
    public enum Route { REBUILD, FLIP, OFFLINE }

    /** 单行变更（CHANGE/ADD/DELETE），供预览逐行展示 + execute 组装 newRows。 */
    public static final class RowChange {
        public String op; // CHANGE / ADD / DELETE
        public UUID v6Id; // null for ADD
        /** 物理列 → 新值（CHANGE/ADD 用于写入；DELETE 不需要）。 */
        public Map<String, Object> newValues = new LinkedHashMap<>();
        /** 物理列 → 旧值（CHANGE/DELETE 展示用；从当前 DB pending/current 行读出）。 */
        public Map<String, Object> oldValues = new LinkedHashMap<>();
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

        /** REBUILD 路径：交给 writer 的有效行集（物理列 → 值，已含 groupKeyAxis 外的所有内容列）。 */
        public final List<Map<String, Object>> effectiveNewRows = new ArrayList<>();

        /** 主从表标记（material_bom_item/element_bom_item 子表）。 */
        public boolean masterDetail;
        public String masterTable;
        public Map<String, Object> masterFixedColumns = Map.of();

        /** 预览展示：逐行 CHANGE/ADD/DELETE 分类。 */
        public final List<RowChange> rowChanges = new ArrayList<>();
    }
}
