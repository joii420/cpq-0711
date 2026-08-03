package com.cpq.quotation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * BL-0092：孤儿 pending 占号的体检与清理。
 *
 * <p><b>要解决什么</b>：V6 基础资料的 9 张表用 {@code pending_quotation_id} 标记"某张在途报价单的
 * 草稿数据"。正常生命周期有两个出口——核价通过转正（{@code QuoteBackfillService}，清空该列）与
 * 删单回收（{@code QuotationService#cleanupPendingV6Data}，DELETE 整行）。两条路径都是完备的
 * （2026-08-02 实测验证：造 pending 行 → 调 DELETE 端点 → 残留 0）。
 *
 * <p><b>那为什么还需要本服务</b>：{@code pending_quotation_id} <b>没有外键约束</b>，因此任何
 * <b>绕过应用层</b>的操作都会留下永久僵尸，且系统内没有任何机制能发现它们：
 * <ul>
 *   <li>迁库 / 建库脚本重建 {@code quotation} 表（实测 2026-07-27~28 迁到 {@code cpq_db_0724}
 *       时产生 241 条孤儿，横跨 8 张表）；</li>
 *   <li>DBA 直接 {@code DELETE FROM quotation}；</li>
 *   <li>{@code material_master} 的<b>引用守卫有意留下的悬空</b>——
 *       {@link MaterialMasterRepository#deletePendingWithGuard} 删不掉仍被引用的行，
 *       只打一行 WARN 日志说"需人工核查引用方"，但从来没有兜底机制去核查。</li>
 * </ul>
 *
 * <p><b>孤儿的危害</b>：这些行 {@code is_current=false} 且归属的报价单已不存在，
 * 永远不会被业务查询命中，却仍占着唯一约束的位置（后续导入同键数据会撞 uq），
 * 且让「从已有产品添加」这类以 {@code pending_quotation_id IS NULL} 为条件的查询返空
 * （实测 {@code material_customer_map} 曾因此对所有客户返空）。
 *
 * <p><b>本服务的语义与删单回收完全一致</b>：8 张表直接 DELETE，{@code material_master} 走同款
 * 引用守卫（删不掉的保留并计入 {@code guardedSurvivors}，需人工核查）。区别只在筛选条件
 * 从「属于某张单」换成「归属的单已不存在」。
 */
@ApplicationScoped
public class PendingHygieneService {

    private static final Logger LOG = Logger.getLogger(PendingHygieneService.class);

    /**
     * 与 {@code QuotationService.B8_PENDING_TABLES} 同一份清单（删单回收用的 8 张表）。
     * <b>新增第 10 张带 {@code pending_quotation_id} 的表时，两处必须同步</b>——
     * 本服务的 {@link #inspect()} 会用 {@code information_schema} 全表扫描做交叉校验，
     * 漏加会在体检结果的 {@code unmanagedTables} 里暴露出来，不会静默。
     */
    static final List<String> PENDING_TABLES = List.of(
            "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
            "capacity", "plating_scheme", "material_customer_map");

    /** {@code material_master} 单独处理（走引用守卫，不能盲删）。 */
    static final String MATERIAL_MASTER = "material_master";

    @Inject
    EntityManager em;

    @Inject
    MaterialMasterRepository materialMasterRepository;

    /** 体检结果：每张表的孤儿数 + 未被本服务管理的表清单。 */
    public record InspectResult(
            Map<String, Long> orphanCountByTable,
            long totalOrphans,
            List<String> unmanagedTables) {}

    /** 清理结果：每张表实删条数 + material_master 被守卫拦下的条数。 */
    public record CleanupResult(
            Map<String, Integer> deletedByTable,
            int totalDeleted,
            int guardedSurvivors,
            boolean dryRun) {}

    /**
     * 体检：统计各表孤儿 pending 数量，不做任何修改。
     *
     * <p>同时用 {@code information_schema} 反查所有带 {@code pending_quotation_id} 的表，
     * 与本服务的管理清单做差集——若有表不在清单内，说明有人加了新的 pending 表却没同步
     * 这里（和 {@code QuotationService.B8_PENDING_TABLES}），列入 {@code unmanagedTables} 告警。
     */
    public InspectResult inspect() {
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (String table : allManagedTables()) {
            long n = countOrphans(table);
            counts.put(table, n);
            total += n;
        }
        List<String> unmanaged = findUnmanagedPendingTables();
        if (!unmanaged.isEmpty()) {
            LOG.warnf("[BL-0092] 发现未纳入 pending 清理清单的表 %s —— 新增 pending 表时必须同步 "
                    + "PendingHygieneService.PENDING_TABLES 与 QuotationService.B8_PENDING_TABLES", unmanaged);
        }
        return new InspectResult(counts, total, unmanaged);
    }

    /**
     * 清理孤儿 pending 行。
     *
     * @param dryRun true = 只统计不删除（返回的 deletedByTable 是"将会删除"的条数）
     */
    @Transactional
    public CleanupResult cleanup(boolean dryRun) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        int total = 0;

        for (String table : PENDING_TABLES) {
            int n = dryRun ? (int) countOrphans(table) : deleteOrphans(table);
            deleted.put(table, n);
            total += n;
        }

        // material_master：走引用守卫，删不掉的留下（与删单回收同语义）
        int mmBefore = (int) countOrphans(MATERIAL_MASTER);
        int mmDeleted = dryRun ? 0 : materialMasterRepository.deleteOrphanPendingWithGuard();
        int mmSurvivors = dryRun ? mmBefore : (int) countOrphans(MATERIAL_MASTER);
        deleted.put(MATERIAL_MASTER, dryRun ? mmBefore : mmDeleted);
        total += dryRun ? mmBefore : mmDeleted;

        if (mmSurvivors > 0) {
            LOG.warnf("[BL-0092] material_master 仍有 %d 条孤儿 pending 被引用守卫拦下"
                    + "（本次已删 %d 条）——这些料号仍被其它 pending/正式数据引用，"
                    + "需人工核查引用方后决定是否强制清理", mmSurvivors, mmDeleted);
        }

        LOG.infof("[BL-0092] 孤儿 pending 清理完成 dryRun=%s 总计=%d 明细=%s 守卫拦下=%d",
                dryRun, total, deleted, mmSurvivors);

        return new CleanupResult(deleted, total, mmSurvivors, dryRun);
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private List<String> allManagedTables() {
        List<String> all = new ArrayList<>(PENDING_TABLES);
        all.add(MATERIAL_MASTER);
        return all;
    }

    /** 表名来自本类的常量白名单，不接受外部输入，故拼接安全。 */
    private long countOrphans(String table) {
        Object n = em.createNativeQuery(
                "SELECT count(*) FROM " + table + " x "
                + "WHERE x.pending_quotation_id IS NOT NULL "
                + "  AND NOT EXISTS (SELECT 1 FROM quotation q WHERE q.id = x.pending_quotation_id)")
            .getSingleResult();
        return ((Number) n).longValue();
    }

    private int deleteOrphans(String table) {
        return em.createNativeQuery(
                "DELETE FROM " + table + " x "
                + "WHERE x.pending_quotation_id IS NOT NULL "
                + "  AND NOT EXISTS (SELECT 1 FROM quotation q WHERE q.id = x.pending_quotation_id)")
            .executeUpdate();
    }

    /**
     * 反查所有带 {@code pending_quotation_id} 列的业务表，减去本服务管理的清单。
     * 排除临时/备份表（{@code %_backup_%} / {@code %_bak} / {@code tmp_%}）。
     */
    @SuppressWarnings("unchecked")
    private List<String> findUnmanagedPendingTables() {
        List<String> found = em.createNativeQuery(
                "SELECT table_name FROM information_schema.columns "
                + "WHERE column_name = 'pending_quotation_id' "
                + "  AND table_schema = 'public' "
                + "  AND table_name NOT LIKE '%\\_backup\\_%' "
                + "  AND table_name NOT LIKE '%\\_bak' "
                + "  AND table_name NOT LIKE 'tmp\\_%' "
                + "ORDER BY table_name").getResultList();
        List<String> managed = allManagedTables();
        List<String> unmanaged = new ArrayList<>();
        for (String t : found) {
            if (!managed.contains(t)) unmanaged.add(t);
        }
        return unmanaged;
    }
}
