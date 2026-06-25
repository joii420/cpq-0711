package com.cpq.datasource.sqlview;

import com.cpq.component.dto.BomClosureResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前请求线程的 spineKeys 三元组上下文（ThreadLocal，仿 {@link com.cpq.formula.dataloader.PartVersionContext}）。
 *
 * <p>承载核价卡片 BOM 闭包的去重三元组 {@code (子件料号, 父件料号, 子件自身当前版本)}，
 * 三个 List 按下标一一对齐。{@link SqlViewExecutor} 执行含 {@code :spineKeys(...)} 宏的 SQL 视图时，
 * 读本上下文把三个 {@code text[]} 注入命名参数 {@code __skP / __skPP / __skV}。
 *
 * <p>务必在 finally 块 {@link #clear()}，避免线程池下个请求误用旧值。
 */
public final class SpineKeysContext {

    /** 去重后的三元组（三个 List index 对齐）。 */
    public static final class Triples {
        public final List<String> partNos;   // k.p ← spine.hfPartNo
        public final List<String> parentNos; // k.pp ← spine.parentNo
        public final List<String> versions;  // k.v ← spine.bomVersion（子件自身当前版本）

        public Triples(List<String> partNos, List<String> parentNos, List<String> versions) {
            this.partNos = partNos;
            this.parentNos = parentNos;
            this.versions = versions;
        }
    }

    private static final ThreadLocal<Triples> CURRENT = new ThreadLocal<>();

    private SpineKeysContext() {}

    /** 设置当前线程三元组；传 null 等价 clear()。 */
    public static void set(Triples triples) {
        if (triples == null) CURRENT.remove();
        else CURRENT.set(triples);
    }

    /** 返回当前线程三元组；未设置返 null。 */
    public static Triples get() {
        return CURRENT.get();
    }

    /** finally 块必调。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 从闭包 spine 构造去重三元组（按 {@code 子件料号|父件料号|版本} 复合键去重，保持首次出现顺序）。
     * <p>NULL 用哨兵 {@code " "} 参与去重键（避免 null 拼接歧义），但写入 List 时仍存原始 null。
     */
    public static Triples fromClosure(BomClosureResult closure) {
        List<String> p = new ArrayList<>();
        List<String> pp = new ArrayList<>();
        List<String> v = new ArrayList<>();
        if (closure == null || closure.spine == null) return new Triples(p, pp, v);
        Set<String> seen = new LinkedHashSet<>();
        for (BomClosureResult.SpineNode n : closure.spine) {
            String key = nz(n.hfPartNo) + "" + nz(n.parentNo) + "" + nz(n.bomVersion);
            if (seen.add(key)) {
                p.add(n.hfPartNo);
                pp.add(n.parentNo);
                v.add(n.bomVersion);
            }
        }
        return new Triples(p, pp, v);
    }

    /**
     * #3 多 line spineKeys 合桶:对<b>全单多个闭包</b>取三元组并集(同款复合键去重)。
     * 用于把 spineKeys 视图从逐行 expand 提升为整单一次 expandMulti(命中前提:见 {@link #maxTriplesPerPart}==1)。
     */
    public static Triples fromClosures(java.util.Collection<BomClosureResult> closures) {
        List<String> p = new ArrayList<>();
        List<String> pp = new ArrayList<>();
        List<String> v = new ArrayList<>();
        if (closures == null) return new Triples(p, pp, v);
        Set<String> seen = new LinkedHashSet<>();
        for (BomClosureResult closure : closures) {
            if (closure == null || closure.spine == null) continue;
            for (BomClosureResult.SpineNode n : closure.spine) {
                String key = nz(n.hfPartNo) + "" + nz(n.parentNo) + "" + nz(n.bomVersion);
                if (seen.add(key)) { p.add(n.hfPartNo); pp.add(n.parentNo); v.add(n.bomVersion); }
            }
        }
        return new Triples(p, pp, v);
    }

    /**
     * #3 安全闸门:全单 spine 里,<b>同一 partNo(子件)对应的不同 (父件,版本) 三元组的最大数量</b>。
     * <p>==1 → 每 partNo 唯一三元组 → 「union spineKeys + 按 partNo 回配」逐位等价(spine 平,合桶安全)。
     * <p>&gt;1 → 同料号多三元组(真多节点 BOM 树)→ partNo 回配会过量收行 → <b>必须回落逐行</b>(不合桶)。
     */
    public static int maxTriplesPerPart(java.util.Collection<BomClosureResult> closures) {
        if (closures == null) return 0;
        java.util.Map<String, Set<String>> byPart = new java.util.HashMap<>();
        for (BomClosureResult closure : closures) {
            if (closure == null || closure.spine == null) continue;
            for (BomClosureResult.SpineNode n : closure.spine) {
                if (n.hfPartNo == null) continue;
                byPart.computeIfAbsent(n.hfPartNo, k -> new java.util.HashSet<>())
                      .add(nz(n.parentNo) + "" + nz(n.bomVersion));
            }
        }
        int max = 0;
        for (Set<String> s : byPart.values()) max = Math.max(max, s.size());
        return max;
    }

    private static String nz(String s) { return s == null ? " " : s; }
}
