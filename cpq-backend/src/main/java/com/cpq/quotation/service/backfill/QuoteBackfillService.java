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
    }

    /**
     * 执行回填（核价通过同事务内调用）。
     * @return 回填摘要（api.md §1.2 响应体 {@code backfill} 字段）
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Summary execute(UUID quotationId, UUID currentUserId) {
        QuoteBackfillPlan plan = collector.collect(quotationId);
        Summary summary = new Summary();

        for (QuoteBackfillPlan.GroupChange g : plan.groups) {
            switch (g.route) {
                case REBUILD -> { executeRebuild(g); summary.versionedGroups++; }
                case FLIP -> { executeFlip(g, quotationId); summary.versionedGroups++; }
                case OFFLINE -> { executeOffline(g, quotationId); summary.versionedGroups++; }
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

        // B9：主档暂存 → 覆盖式 upsert 进 material_master（preserveDescriptive=true，见 repo 注释）。
        materialMasterRepo.promoteStaging(quotationId, currentUserId);
        // Q6：ADD 行引入的全新料号补 stub（已存在则不覆盖，upsertByMaterialNo preserveDescriptive=true 天然满足）。
        for (Map.Entry<String, String> e : plan.newMaterialStubs.entrySet()) {
            materialMasterRepo.upsertByMaterialNo(e.getKey(), e.getValue(), null, null, null, null, null,
                null, null, null, currentUserId, true);
        }

        // B7：占号表 pending → approved（与回填同事务，不会出现"数据生效但产品还看不见"的中间态）。
        flipMaterialCustomerMap(quotationId);

        // 清理：本单在 8 张 pending 表 + 主档暂存表的残留行（升版/flip/offline 已产生正式行，
        // pending 草稿使命完成）。
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

    /** 路径②：无 snapshot 表征的纯 pending 组——直接 flip is_current + 按 pending_supersedes 降旧版。 */
    private void executeFlip(QuoteBackfillPlan.GroupChange g, UUID quotationId) {
        runUpdate("UPDATE " + g.table + " SET is_current = true, pending_quotation_id = NULL " +
                "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis), quotationId, g.groupKeyAxis);
        runUpdate("UPDATE " + g.table + " SET is_current = false " +
                "WHERE id IN (SELECT unnest(pending_supersedes) FROM " + g.table + " " +
                "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis) + ")", quotationId, g.groupKeyAxis);
        if (g.masterDetail) {
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(g.table);
            String masterTable = spec.master.masterTable;
            runUpdate("UPDATE " + masterTable + " SET is_current = true, pending_quotation_id = NULL " +
                    "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis), quotationId, g.groupKeyAxis);
            runUpdate("UPDATE " + masterTable + " SET is_current = false " +
                    "WHERE id IN (SELECT unnest(pending_supersedes) FROM " + masterTable + " " +
                    "WHERE pending_quotation_id = :qid AND " + axisWhere(g.groupKeyAxis) + ")", quotationId, g.groupKeyAxis);
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

    private void cleanupPending(UUID quotationId) {
        for (String table : PENDING_TABLES) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE pending_quotation_id = :qid")
                .setParameter("qid", quotationId)
                .executeUpdate();
        }
        materialMasterRepo.clearStaging(quotationId);
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
