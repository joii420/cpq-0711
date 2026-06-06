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

    private static String nz(String s) { return s == null ? " " : s; }
}
