package com.cpq.dataset.importer;

import com.cpq.dataset.support.SqlIdent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主数据存在性校验（task-260902 · B-6 · 需求文档 R-7 · AC-8）。
 *
 * <p>主数据<b>共享不拆</b>（闸门 A0 D-16），本类<b>只读</b>现有 {@code element} / {@code process_master} /
 * {@code material_recipe} / {@code customer}，一个字节都不写。
 *
 * <p>🚫 <b>N+1 硬指标（B-12 / AC-44）</b>：每个 masterType <b>一条</b> {@code IN (...)} 查询查完全部待校验编码，
 * 与行数 / 料号数无关。🚫 严禁在校验循环里逐行查。
 *
 * <p>⚠️ 刻意<b>不</b>校验 {@code material}（料号）：R-7 只列了元素 / 工序 / 材质 / 客户四类。
 * 料号（组成料号、投入料号…）指向的是本数据集<b>自己的物料表</b>，而物料表与引用它的 sheet 在同一份 Excel 里，
 * 校验期（Phase 1 零写库）物料还没落库，按 material_master 校验会把合法文件整份拒收。
 */
@ApplicationScoped
public class MasterDataChecker {

    /** masterType → [表名, 编码列]。与既有 {@code PricingMaintenanceService.MASTER} 同源，只读。 */
    private static final Map<String, String[]> MASTER_TABLES = Map.of(
            "element",  new String[]{"element",         "element_code"},
            "process",  new String[]{"process_master",  "process_no"},
            "recipe",   new String[]{"material_recipe", "code"},
            "customer", new String[]{"customer",        "code"});

    /** 实际参与存在性校验的 masterType（R-7 明列的四类）。 */
    public static final Set<String> CHECKED_TYPES = MASTER_TABLES.keySet();

    @Inject
    EntityManager em;

    public boolean supports(String masterType) {
        return masterType != null && MASTER_TABLES.containsKey(masterType);
    }

    /**
     * 通用批量存在性查询：一次 {@code IN (...)} 查完，返回 {@code values} 中确实存在于
     * {@code table.column} 的子集。
     *
     * <p>轴值登记校验（D-24 / AC-52）用它查数据集自己的物料表。
     * 🚫 <b>一条 SQL 查完全部候选值</b>，严禁逐值查（B-12 / AC-44 的 N+1 硬指标）。
     * 表名 / 列名来自 Registry（可信），仍经 {@link SqlIdent} 白名单校验后才拼进 SQL。
     */
    @SuppressWarnings("unchecked")
    public Set<String> existingIn(String table, String column, Collection<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        String t = SqlIdent.of(table);
        String c = SqlIdent.of(column);
        List<Object> found = em.createNativeQuery(
                        "SELECT " + c + " FROM " + t + " WHERE " + c + " IN (:vals)")
                .setParameter("vals", values)
                .getResultList();
        Set<String> out = new HashSet<>(found.size() * 2);
        for (Object o : found) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /**
     * 批量查存在的编码。
     *
     * @return codes 中<b>确实存在</b>于主数据表里的子集
     */
    @SuppressWarnings("unchecked")
    public Set<String> existing(String masterType, Collection<String> codes) {
        String[] m = MASTER_TABLES.get(masterType);
        if (m == null || codes == null || codes.isEmpty()) return Set.of();
        List<Object> found = em.createNativeQuery(
                        "SELECT " + m[1] + " FROM " + m[0] + " WHERE " + m[1] + " IN (:codes)")
                .setParameter("codes", codes)
                .getResultList();
        Set<String> out = new HashSet<>(found.size() * 2);
        for (Object o : found) if (o != null) out.add(String.valueOf(o));
        return out;
    }
}
