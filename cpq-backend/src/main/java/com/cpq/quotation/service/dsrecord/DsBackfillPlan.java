package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.SheetDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「核价通过将写入什么」的计划（task-260907 第二段 · B-9 / B-10 / B-11）。
 *
 * <h3>🚨 本类描述的是<b>结果状态</b>，不是增量</h3>
 * {@code AP-60} 判据四：原实现按「逐列 diff」判断有无变更，{@code rd.changes.isEmpty() → continue}，
 * 于是「组内未被页签表征的行将被删除」这件事<b>根本不在 diff 模型里</b> ——
 * 预览显示 0 变更，执行删 3 行。
 * ⇒ 本类的 {@link Group#resultRows} 是<b>回填后该组会长成什么样</b>的完整行集，
 * {@link Group#untouchedRows} / {@link Group#preservedColumns} 是它的两个可见守卫。
 */
public class DsBackfillPlan {

    public UUID quotationId;
    public String customerNo;
    /** false = 本单没有任何组件绑定到 {@code ds_quote_*} 带版本表（老单 / 存量手写视图）→ 不走新回填。 */
    public boolean applicable;
    public final List<Table> tables = new ArrayList<>();

    /** 锚不上的 {@code _record} 行（A0-1 第三支路）。🚫 不静默丢弃、也不静默当新增行插进去。 */
    public static class Unanchored {
        public Long recordId;
        public Long originId;
        public String baseRowFingerprint;
        /** 给财务看的少量识别值（轴列 + 该 sheet 的粒度列）。 */
        public final Map<String, Object> displayValues = new LinkedHashMap<>();
        /** {@code SAME_VERSION_ORIGIN_MISS} / {@code CROSS_VERSION_FINGERPRINT_MISS} / {@code NO_ANCHOR} */
        public String reason;
    }

    public static class Group {
        public String axisValue;
        public String customerNo;
        /** {@code _record} 拍快照时主表该组的版本。 */
        public int baseVersionNo;
        /** 库里当前版本；组不存在时 0。 */
        public int currentVersionNo;
        /**
         * 将升到的版本 = {@code max(当前, _history 最大) + 1}。
         * <p>⚠️ <b>这是「预览用的预测值」，不是权威值</b>：权威版本号由
         * {@code VersionedGroupWriter.writeGroups} 在执行那一刻产出（AC-9 明令不另立升版实现）。
         * 预览必须给财务看「将升到几」（AC-10①），dry-run 只能预测；执行后回报的是 writer 的真实返回值。
         * <p>{@code result=UNCHANGED} 时等于 {@link #currentVersionNo}。
         */
        public int targetVersionNo;
        /** {@code baseVersionNo != currentVersionNo} —— 库里已被别的单改过，走指纹重锚。 */
        public boolean crossVersion;
        /** {@code CREATED} / {@code UPGRADED} / {@code UNCHANGED} / {@code BLOCKED}（C′，D-37）。 */
        public String result;
        /** {@code BLOCKED} 时非空。目前只有 {@code GRAIN_KEY_COLLISION}，做成枚举以便扩。 */
        public String blockedReason;
        /** {@code BLOCKED} 时非空：触发 C′ 判据的那些粒度键。 */
        public final List<Colliding> collidingRows = new ArrayList<>();

        /** 主表当前整组行数（= 基底行数）。 */
        public int baseRowCount;
        /** {@code _record} 表征并覆盖了列的行数。 */
        public int patchedRows;
        /** 🔑 页签没表征、本次原样保留的行数 —— AP-60 的守卫，必须出现在预览里。 */
        public int untouchedRows;
        /** 锚不上的行明细。 */
        public final List<Unanchored> unanchoredRows = new ArrayList<>();

        /** 该页签表征并会被覆盖的列。 */
        public final Set<String> patchedColumns = new LinkedHashSet<>();
        /** 页签没表征、本次原样保留的列（🚫 不得写 NULL）。 */
        public final Set<String> preservedColumns = new LinkedHashSet<>();

        /**
         * 回填后该组的<b>完整</b>行集 —— 直接作为 {@code VersionedGroupWriter.writeGroups} 的入参。
         * <p>构成 = 主表原整组行（基底，逐行保留）⊕ {@code _record} 的列级 patch
         * ⊕ 锚不上的行按新增追加（AC-20 第三支路③，财务确认后才写）。
         * <p>🚫 <b>不是</b>「遍历 {@code _record} 生成行」—— 那正是 AP-60 的事故形状。
         */
        public final List<Map<String, Object>> resultRows = new ArrayList<>();
    }

    /** C′（D-37）：一个「本该锚上却没锚上、而其粒度键在基底里确实存在」的粒度键。 */
    public static class Colliding {
        public final Map<String, Object> grainKey = new LinkedHashMap<>();
        /** 该粒度键在**基底**里有几行 —— &gt;1 就是歧义源头。 */
        public int baseRowCount;
        /** 该粒度键在 {@code _record} 里有几行。 */
        public int recordRowCount;
    }

    public static class Table {
        public SheetDef sheet;
        public final List<Group> groups = new ArrayList<>();
    }

    /**
     * 🆕 D-35：本单的「{@code _record} 快照过期」标记（写 {@code _record} 失败留下的）。
     * <p>非空 = <b>预览此刻读到的 {@code _record} 可能是过期或缺失的</b>。
     * 🚫 不许静默 —— 见 {@code DsRecordStaleService} 类注释的后果链。
     */
    public DsRecordStaleService.Stale recordStale;

    /**
     * 🆕 D-33：本单里<b>不参与</b>基础数据升版的组件（手写视图无 {@code builder_config} 等）。
     *
     * <p>🚫 <b>不许静默丢弃</b>。实测现网 156/228 个组件视图是手写的 ⇒ 不告知，财务会以为
     * 「核价通过把这单的数据全升版了」，而实际上大半个单子根本没参与 ——
     * 与 {@code AP-60} 判据四（「不写 = 删除」不在 diff 模型里）是同一种静默。
     */
    public final List<DsSheetBindingResolver.NonParticipating> nonParticipating = new ArrayList<>();

    /** 只落 {@code extend_column}、不回填的字段（AC-3 / api.md {@code extendColumnOnly}）。 */
    public final Map<String, Set<String>> extendColumnOnly = new LinkedHashMap<>();
}
