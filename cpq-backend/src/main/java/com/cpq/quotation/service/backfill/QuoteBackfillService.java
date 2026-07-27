package com.cpq.quotation.service.backfill;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0721 报价数据版本升级 · B5.3/B5.4 + B7(闸门联动) + B9(主档促升) —— 回填执行器。
 *
 * <p>在 {@code QuotationService.costingApprove} 的同一事务内被调用（本类方法不单独声明
 * {@code @Transactional} 边界之外的新事务，沿用调用方事务，整体失败整体回滚——backtask B5.4）。
 */
@ApplicationScoped
public class QuoteBackfillService {

    private static final Logger LOG = Logger.getLogger(QuoteBackfillService.class);

    @Inject QuoteBackfillCollector collector;
    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;
    @Inject EntityManager em;

    /** 报价升版逻辑受管的 8 张 pending 表（7 版本化 + 占号表），回填完成后统一清理残留 pending。 */
    private static final List<String> PENDING_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "material_customer_map");

    public static final class Summary {
        public int versionedGroups, addedRows, deletedRows, changedRows;
        /** repair-0727 B4（api.md §2）：涉及产品数，与预览 {@code summary.affectedProducts} 同口径。 */
        public int affectedProducts;
    }

    /**
     * 执行回填（核价通过同事务内调用）。
     * @return 回填摘要（api.md §1.2 响应体 {@code backfill} 字段）
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Summary execute(UUID quotationId, UUID currentUserId) {
        QuoteBackfillPlan plan = collector.collect(quotationId);
        Summary summary = new Summary();
        java.util.Set<String> affectedProducts = new java.util.LinkedHashSet<>();

        for (QuoteBackfillPlan.GroupChange g : plan.groups) {
            String productNo = QuoteTableAxis.productNoOf(g.table, g.groupKeyAxis);
            if (productNo != null) affectedProducts.add(productNo);
            switch (g.route) {
                case REBUILD -> { executeRebuild(g); summary.versionedGroups++; }
                case FLIP -> { executeFlip(g, quotationId); summary.versionedGroups++; }
                case OFFLINE -> { executeOffline(g, quotationId); summary.versionedGroups++; }
                // repair-0727 裁决①：NOOP 组在 QuoteBackfillCollector.collect() 里已被整组过滤，
                // 永远不会出现在 plan.groups 里；这里显式列出空分支只为防御未来有人绕过收集器
                // 直接塞 NOOP 进 plan（不写库、不计入摘要，语义上等价于"什么都不做"）。
                case NOOP -> { }
            }
            for (QuoteBackfillPlan.RowChange rc : g.rowChanges) {
                switch (rc.op) {
                    case "ADD" -> summary.addedRows++;
                    case "DELETE" -> summary.deletedRows++;
                    case "CHANGE" -> summary.changedRows++;
                    default -> { }
                }
            }
        }
        summary.affectedProducts = affectedProducts.size();

        // repair-0726 B3：核价通过 → 本单 pending 料号转正（已直落正表，无需再覆盖式 upsert）。
        materialMasterRepo.flipPending(quotationId);
        // Q6：ADD 行引入的全新料号补 stub（已存在则不覆盖，upsertByMaterialNo preserveDescriptive=true 天然满足）。
        for (Map.Entry<String, String> e : plan.newMaterialStubs.entrySet()) {
            materialMasterRepo.upsertByMaterialNo(e.getKey(), e.getValue(), null, null, null, null, null,
                null, null, null, currentUserId, true);
        }

        // B7：占号表 pending → approved（与回填同事务，不会出现"数据生效但产品还看不见"的中间态）。
        flipMaterialCustomerMap(quotationId);

        // 清理：本单在 8 张 pending 表的残留行（升版/flip/offline 已产生正式行，pending 草稿使命
        // 完成）。material_master 不在此列——它已被上面的 flipPending 转正，见 cleanupPending 注释。
        cleanupPending(quotationId);

        return summary;
    }

    private void executeRebuild(QuoteBackfillPlan.GroupChange g) {
        if (g.masterDetail) {
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(g.table);
            writer.writeVersionedMasterDetail(
                spec.master.masterTable, spec.master.masterVersionColumn, g.groupKeyAxis, g.masterFixedColumns,
                g.table, spec.versionColumn, g.groupKeyAxis, g.contentColumns, g.effectiveNewRows, null);
        } else {
            writer.writeVersionedGroup(new VersionedGroupSpec(
                g.table, QuoteTableAxis.of(g.table).versionColumn, g.groupKeyAxis, g.contentColumns,
                g.effectiveNewRows, null, null));
        }
    }

    /** 路径②：无 snapshot 表征的纯 pending 组——按 pending_supersedes 先降旧版，再 flip is_current。
     *  task-0721 Bug B 修复：必须先用 pending_supersedes 降级旧官方 current 行，再清
     *  pending_quotation_id——原顺序先清空该列会导致降级旧版本的子查询
     *  {@code WHERE pending_quotation_id = :qid} 命中不到本行（已被上一条 UPDATE 清空），
     *  子查询恒空，旧组永远不会被降级，造成同组两行 is_current=true 并存。 */
    private void executeFlip(QuoteBackfillPlan.GroupChange g, UUID quotationId) {
        runUpdate("UPDATE " + g.table + " SET is_current = false " +
                "WHERE id IN (SELECT unnest(pending_supersedes) FROM " + g.table + " " +
                "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis) + ")", quotationId, g.groupKeyAxis);
        runUpdate("UPDATE " + g.table + " SET is_current = true, pending_quotation_id = NULL " +
                "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis), quotationId, g.groupKeyAxis);
        if (g.masterDetail) {
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(g.table);
            String masterTable = spec.master.masterTable;
            runUpdate("UPDATE " + masterTable + " SET is_current = false " +
                    "WHERE id IN (SELECT unnest(pending_supersedes) FROM " + masterTable + " " +
                    "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis) + ")", quotationId, g.groupKeyAxis);
            runUpdate("UPDATE " + masterTable + " SET is_current = true, pending_quotation_id = NULL " +
                    "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis), quotationId, g.groupKeyAxis);
        }
    }

    /** 路径③：整组下线——降本组当前 is_current 行（含本单 pending 取代的旧 current），不写新版本。 */
    private void executeOffline(QuoteBackfillPlan.GroupChange g, UUID quotationId) {
        runUpdate("UPDATE " + g.table + " SET is_current = false " +
                "WHERE is_current = true AND pending_quotation_id IS NULL AND " + axisWhere(g.groupKeyAxis),
                null, g.groupKeyAxis);
        if (g.masterDetail) {
            String masterTable = QuoteTableAxis.of(g.table).master.masterTable;
            runUpdate("UPDATE " + masterTable + " SET is_current = false " +
                    "WHERE is_current = true AND pending_quotation_id IS NULL AND " + axisWhere(g.groupKeyAxis),
                    null, g.groupKeyAxis);
        }
        // 该组 pending 行随 cleanupPending 统一清理（DELETE ... WHERE pending_quotation_id=:qid）。
    }

    private void flipMaterialCustomerMap(UUID quotationId) {
        em.createNativeQuery(
                "UPDATE material_customer_map SET pending_quotation_id = NULL WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId)
            .executeUpdate();
    }

    /**
     * repair-0726 B3：material_master 不在此清理——本单 pending 料号已被上面的
     * {@code flipPending} 转正（pending_quotation_id 清为 NULL），此处若再对它做 DELETE 语义
     * 的清理会把刚转正的正式行删掉。8 张表清单继续保持 material_master 不在其中。
     *
     * <p><b>repair-0727 验收澄清（技术总监裁决，避免后人再怀疑一次）</b>：这里对本单在 8 张表里
     * 残留的 pending 行做<b>物理删除</b>，不区分该行是「被 REBUILD 消费掉的基底行」「被墓碑显式
     * 删除的行」还是「FLIP/OFFLINE 路径未被写入器碰过的行」——三种情形处理方式统一，理由是：
     * 这些行<b>从未 {@code is_current=true} 过</b>，只是本单一次导入产生的草稿，物理删除它们没有
     * 丢失任何"曾经生效"的数据，正是需求说明 §规则七/AC-13"删单级联删 pending"同一套语义在这里的
     * 延伸（删单是整批级联删，这里是回填成功后的批量清理，对象都是"从未生效过的纯 pending 行"）。
     *
     * <p>与之相对、真正需要"降 {@code is_current=false} 物理留存可审计"的是需求说明 §规则四"墓碑行"
     * 讲的<b>老版本</b>——那指的是被本单 {@code pending_supersedes} 指针点名的<b>曾经 is_current=true
     * 的官方行</b>（同款语义见
     * {@code com.cpq.datasource.sqlview.QuotePendingRewriterOfficialVisibilityAndSupersedesTest}；
     * {@code QuoteBackfillFlatAcceptanceTest#deleteRoute_tombstonedRowExcluded_oldRowPhysicallyRetained}
     * 里断言留存的是 {@code delOfficial} 而不是 {@code delPending}）——这条规则只保护"曾经真实生效过"
     * 的行，不适用于本方法清理的这批"从未生效过"的草稿行，两者不是同一件事，不要混为一谈。
     */
    private void cleanupPending(UUID quotationId) {
        for (String table : PENDING_TABLES) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE pending_quotation_id = :qid")
                .setParameter("qid", quotationId)
                .executeUpdate();
        }
    }

    /** 组轴 NULL 安全 WHERE 片段：{@code col IS NOT DISTINCT FROM :ax_col AND ...}。 */
    private static String axisWhere(Map<String, Object> axis) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String col : axis.keySet()) {
            if (i++ > 0) sb.append(" AND ");
            sb.append(col).append(" IS NOT DISTINCT FROM :ax_").append(col);
        }
        return sb.isEmpty() ? "TRUE" : sb.toString();
    }

    /** 跑一条按轴绑定（+可选 qid）的原生 UPDATE。qid==null 时不绑定/不含 :qid 占位符。 */
    private void runUpdate(String sql, UUID quotationId, Map<String, Object> axis) {
        jakarta.persistence.Query query = em.createNativeQuery(sql);
        if (quotationId != null) query.setParameter("qid", quotationId);
        for (Map.Entry<String, Object> e : axis.entrySet()) query.setParameter("ax_" + e.getKey(), e.getValue());
        query.executeUpdate();
    }
}
