package com.cpq.configure.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 材质含量配置的共享判据（task-260901）—— <b>纯函数，不碰库</b>。
 *
 * <p>🚨 <b>本类是「UI 新建材质」与「Excel 导入」两个入口的唯一判据来源</b>（M-0a）。
 * {@link MaterialRecipeRules#findFirstElementSetMismatch} 同时被
 * {@code MaterialRecipeService#create}（AC-33/AC-34）与
 * {@code MaterialRecipeImportService}（AC-32）调用；
 * {@link MaterialRecipeRules#sameContent} 同时承担 CRUD 的 {@code CONFIG_DUPLICATED}
 * 与导入的 {@code configsSkippedAsDuplicate}（M-4）。
 * 🚫 <b>不许在任一侧另写一份</b> —— 那是「两处口径分叉」的种子，测试有专门的证伪实验 FT-4 盯这一点。
 */
public final class MaterialRecipeRules {

    /** 0~1 制的合计目标与容差（导入侧口径，沿用既有值）。 */
    public static final BigDecimal SUM_TARGET_RATIO = BigDecimal.ONE;
    public static final BigDecimal SUM_TOLERANCE_RATIO = new BigDecimal("0.02");

    /** 100 制的合计目标与容差（配置 CRUD 口径 = 0~1 制 ×100）。 */
    public static final BigDecimal SUM_TARGET_PCT = new BigDecimal("100");
    public static final BigDecimal SUM_TOLERANCE_PCT = new BigDecimal("2");

    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private MaterialRecipeRules() {}

    // ── Σ 校验 ────────────────────────────────────────────────

    /** |sum - target| ≤ tolerance。⚠️ 容差只用于「Σ 是不是 1」，<b>不用于</b>「两个配置是不是同一个」（M-4）。 */
    public static boolean sumWithinTolerance(BigDecimal sum, BigDecimal target, BigDecimal tolerance) {
        if (sum == null) return false;
        return sum.subtract(target).abs().compareTo(tolerance) <= 0;
    }

    /** 0~1 制：Σ ≈ 1（容差 0.02）。 */
    public static boolean sumIsOneRatio(BigDecimal sum) {
        return sumWithinTolerance(sum, SUM_TARGET_RATIO, SUM_TOLERANCE_RATIO);
    }

    /** 100 制：Σ ≈ 100（容差 2）。 */
    public static boolean sumIsOnePct(BigDecimal sum) {
        return sumWithinTolerance(sum, SUM_TARGET_PCT, SUM_TOLERANCE_PCT);
    }

    /** 报告/报文里的 Σ 展示：一律 0~1 制两位小数（AC-9 的「实际1.20」）。 */
    public static String formatRatioSum(BigDecimal ratioSum) {
        return ratioSum.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 单值范围：0 < v ≤ max（0~1 制 max=1；100 制 max=100）。 */
    public static boolean pctInRange(BigDecimal v, BigDecimal max) {
        return v != null
            && v.compareTo(BigDecimal.ZERO) > 0
            && v.compareTo(max) <= 0;
    }

    // ── M-0a / M-5b：各组元素种类集合必须全相同 ───────────────────

    /**
     * 「同一材质下各组（配方）必须使用相同的元素种类」的<b>唯一判据</b>。
     *
     * <p>实现是「先收齐全部组再判」：拿第 0 组当参照逐个比对，返回<b>第一对</b>不一致的下标
     * {@code [0, j]}；全部一致返回 {@code null}。
     * <p>⚠️ 判据是<b>集合相等</b>（多了、少了都算不一致），不是子集；且结果与组内行序无关
     * （集合语义），故「同样的数据换个行序，入库结果完全相同」（AC-32）。
     *
     * @param groups 各组的元素键集合（导入侧键 = 元素符号；UI 侧键 = elementNo）——同一次调用内键域必须一致
     */
    public static int[] findFirstElementSetMismatch(List<? extends Collection<String>> groups) {
        if (groups == null || groups.size() < 2) return null;
        Set<String> base = new LinkedHashSet<>(groups.get(0));
        for (int j = 1; j < groups.size(); j++) {
            Set<String> other = new LinkedHashSet<>(groups.get(j));
            if (!base.equals(other)) {
                return new int[]{0, j};
            }
        }
        return null;
    }

    /** 集合相等（多了、少了都算不等）—— 单组与材质元素组成比对时用（AC-10 / CONFIG_ELEMENT_SET_MISMATCH）。 */
    public static boolean elementSetsEqual(Collection<String> a, Collection<String> b) {
        return new LinkedHashSet<>(a == null ? List.of() : a)
            .equals(new LinkedHashSet<>(b == null ? List.of() : b));
    }

    // ── M-4：配置的相等判据 ───────────────────────────────────

    /**
     * <b>M-4</b>：两个配置相等 ⇔ 元素集合相同 <b>且</b> 每个元素的含量 {@code BigDecimal.compareTo == 0}
     * （值相等，忽略 scale 差异：{@code 90} 与 {@code 90.000000000000} 相等）。
     * <p>🚫 <b>不套用 Σ 的 0.02 容差</b> —— 容差只回答「Σ 是不是 1」。
     */
    public static boolean sameContent(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        if (a == null || b == null) return false;
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, BigDecimal> e : a.entrySet()) {
            BigDecimal other = b.get(e.getKey());
            if (e.getValue() == null || other == null) return false;
            if (e.getValue().compareTo(other) != 0) return false;
        }
        return true;
    }

    // ── 报文里的集合展示 ──────────────────────────────────────

    /**
     * {@code {Ag,Ni}}（分隔符由调用方给：AC-32 用 {@code ","}，AC-34 用 {@code ", "}）。
     *
     * <p>🚨 <b>元素一律按符号排序输出，不按它们在文件/请求里出现的先后</b> ——
     * AC-32 要求「把行顺序对调后重导，结果<b>逐字</b>相同」，而报文里的集合就是那个「逐字」的一部分。
     * 保插入序会让 {@code {Ag,Ni}} 变成 {@code {Ni,Ag}}，报告就不再逐字相同了
     * （这条是开发自测跑「组内行序对调」时真实撞出来的，不是推测）。
     */
    public static String formatSet(Collection<String> set, String separator) {
        List<String> sorted = new java.util.ArrayList<>(new LinkedHashSet<>(set == null ? List.of() : set));
        java.util.Collections.sort(sorted);
        return "{" + String.join(separator, sorted) + "}";
    }
}
