package com.cpq.quotation.service.backfill;

import com.cpq.quotation.dto.backfill.BackfillGroupDTO;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
import com.cpq.quotation.dto.backfill.BackfillRowDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * task-0721 报价数据版本升级 · B6 —— 回填影响预览 + previewToken。
 *
 * <p>只读 dry-run：与 {@link QuoteBackfillService#execute} 共用同一个 {@link QuoteBackfillCollector}，
 * 不写库（backtask B6"性能：dry-run 与真回填共用同一收集逻辑，避免两套"）。
 *
 * <p><b>previewToken 确定性</b>（需求说明 §12 Q4）：token 是"报价单当前有效状态"的纯函数——
 * 固定排序（表名 → groupKey 规范串 → op → 行身份）+ 数值归一（{@code stripTrailingZeros}，
 * 对齐 {@code VersionedV6Writer#norm} 的比对口径）+ NULL 稳定序列化（{@code "∅"}）。
 * 同一未变状态两次 {@link #preview} 必须得到同一 token；409 只应在预览与提交之间数据真的变了时触发。
 */
@ApplicationScoped
public class QuoteBackfillPreviewService {

    @Inject QuoteBackfillCollector collector;
    @Inject EntityManager em;

    @Transactional(Transactional.TxType.SUPPORTS)
    public BackfillPreviewDTO preview(UUID quotationId) {
        QuoteBackfillPlan plan = collector.collect(quotationId);
        return toDTO(plan);
    }

    /** 核价通过入口用：重算当前有效状态的 token，与提交携带的 token 比对。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean verifyToken(UUID quotationId, String submittedToken) {
        if (submittedToken == null || submittedToken.isBlank()) return false;
        QuoteBackfillPlan plan = collector.collect(quotationId);
        return computeToken(plan).equals(submittedToken);
    }

    private BackfillPreviewDTO toDTO(QuoteBackfillPlan plan) {
        BackfillPreviewDTO dto = new BackfillPreviewDTO();
        dto.quotationId = plan.quotationId;

        List<QuoteBackfillPlan.GroupChange> sorted = new ArrayList<>(plan.groups);
        sorted.sort(Comparator.comparing((QuoteBackfillPlan.GroupChange g) -> g.table)
            .thenComparing(g -> canonAxis(g.groupKeyAxis)));

        for (QuoteBackfillPlan.GroupChange g : sorted) {
            BackfillGroupDTO gd = new BackfillGroupDTO();
            gd.table = g.table;
            gd.tabName = g.tabName;
            gd.groupKey = g.groupKeyAxis;
            gd.isGlobalShared = g.isGlobalShared;
            gd.versionFrom = g.versionFrom;
            gd.versionTo = computeVersionTo(g);

            List<QuoteBackfillPlan.RowChange> rowsSorted = new ArrayList<>(g.rowChanges);
            rowsSorted.sort(Comparator.comparing((QuoteBackfillPlan.RowChange r) -> r.op)
                .thenComparing(r -> r.v6Id == null ? "" : r.v6Id.toString())
                .thenComparing(r -> canonMap(r.newValues)));

            for (QuoteBackfillPlan.RowChange rc : rowsSorted) {
                BackfillRowDTO rd = new BackfillRowDTO();
                rd.op = rc.op;
                rd.v6Id = rc.v6Id;
                if ("CHANGE".equals(rc.op)) {
                    for (Map.Entry<String, Object> e : rc.newValues.entrySet()) {
                        Object oldV = rc.oldValues.get(e.getKey());
                        if (!Objects.equals(norm(oldV), norm(e.getValue()))) {
                            rd.changes.put(e.getKey(), new Object[]{oldV, e.getValue()});
                        }
                    }
                    if (rd.changes.isEmpty()) continue; // 内容完全一致——不计入预览行/摘要，避免误报"改值"
                } else if ("ADD".equals(rc.op)) {
                    rd.values.putAll(rc.newValues);
                } else { // DELETE
                    rd.values.putAll(rc.oldValues);
                }
                gd.rows.add(rd);
                switch (rc.op) {
                    case "ADD" -> dto.summary.addedRows++;
                    case "DELETE" -> dto.summary.deletedRows++;
                    case "CHANGE" -> dto.summary.changedRows++;
                }
            }
            if (!gd.rows.isEmpty() || g.route != QuoteBackfillPlan.Route.REBUILD) {
                dto.summary.versionedGroups++;
                dto.groups.add(gd);
            }
        }
        dto.previewToken = computeToken(plan);
        return dto;
    }

    /** 只读复刻 {@code VersionedV6Writer} 的"max(数字版本)+1"逻辑，供预览展示 versionTo（不写库）。 */
    private String computeVersionTo(QuoteBackfillPlan.GroupChange g) {
        if (g.route == QuoteBackfillPlan.Route.OFFLINE) return null; // 下线，无新版本
        if (g.route == QuoteBackfillPlan.Route.FLIP) return g.versionFrom; // pending 自身版本号转正
        boolean anyChange = g.rowChanges.stream().anyMatch(r ->
            "ADD".equals(r.op) || "DELETE".equals(r.op)
            || ("CHANGE".equals(r.op) && !contentEquals(r.oldValues, r.newValues)));
        if (!anyChange) return g.versionFrom;
        QuoteTableAxis.Spec spec = QuoteTableAxis.of(g.table);
        StringBuilder where = new StringBuilder();
        int i = 0;
        for (String col : g.groupKeyAxis.keySet()) {
            if (i++ > 0) where.append(" AND ");
            where.append(col).append(" IS NOT DISTINCT FROM :ax_").append(col);
        }
        jakarta.persistence.Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + spec.versionColumn + " ~ '^[0-9]+$' THEN "
                + spec.versionColumn + "::int END) FROM " + g.table + " WHERE " + where);
        for (Map.Entry<String, Object> e : g.groupKeyAxis.entrySet()) q.setParameter("ax_" + e.getKey(), e.getValue());
        Object max = q.getSingleResult();
        return max == null ? "2000" : String.valueOf(((Number) max).intValue() + 1);
    }

    private boolean contentEquals(Map<String, Object> a, Map<String, Object> b) {
        Set<String> keys = new HashSet<>(); keys.addAll(a.keySet()); keys.addAll(b.keySet());
        for (String k : keys) if (!Objects.equals(norm(a.get(k)), norm(b.get(k)))) return false;
        return true;
    }

    // ── previewToken 计算：固定排序 + 数值归一 + NULL 稳定序列化，SHA-256 ──

    private String computeToken(QuoteBackfillPlan plan) {
        List<QuoteBackfillPlan.GroupChange> sorted = new ArrayList<>(plan.groups);
        sorted.sort(Comparator.comparing((QuoteBackfillPlan.GroupChange g) -> g.table)
            .thenComparing(g -> canonAxis(g.groupKeyAxis)));

        StringBuilder sb = new StringBuilder();
        for (QuoteBackfillPlan.GroupChange g : sorted) {
            sb.append(g.table).append('|').append(canonAxis(g.groupKeyAxis)).append(';');
            List<QuoteBackfillPlan.RowChange> rows = new ArrayList<>(g.rowChanges);
            rows.sort(Comparator.comparing((QuoteBackfillPlan.RowChange r) -> r.op)
                .thenComparing(r -> r.v6Id == null ? "" : r.v6Id.toString())
                .thenComparing(r -> canonMap(r.newValues)));
            for (QuoteBackfillPlan.RowChange r : rows) {
                if ("CHANGE".equals(r.op) && contentEquals(r.oldValues, r.newValues)) continue; // 无实际差异不进 token
                sb.append(r.op).append(':').append(r.v6Id == null ? "" : r.v6Id).append(':')
                  .append(canonMap(r.newValues)).append(';');
            }
        }
        // 主档暂存 + 新料号 stub 也纳入 token（影响回填结果的一部分状态）。
        List<String> stagingKeys = new ArrayList<>();
        for (var s : plan.materialMasterStaging) stagingKeys.add(canonStaged(s));
        Collections.sort(stagingKeys);
        sb.append("#staging=").append(String.join(",", stagingKeys));
        List<String> stubKeys = new ArrayList<>(plan.newMaterialStubs.keySet());
        Collections.sort(stubKeys);
        sb.append("#stubs=").append(String.join(",", stubKeys));

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String canonAxis(Map<String, Object> axis) {
        List<String> keys = new ArrayList<>(axis.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('=').append(norm(axis.get(k))).append(',');
        return sb.toString();
    }

    private static String canonMap(Map<String, Object> m) {
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('=').append(norm(m.get(k))).append(',');
        return sb.toString();
    }

    private static String canonStaged(com.cpq.basicdata.v6.repository.MaterialMasterRepository.StagedRow s) {
        return s.materialNo() + "|" + norm(s.materialName()) + "|" + norm(s.materialType()) + "|"
            + norm(s.unitWeight()) + "|" + norm(s.productionNo());
    }

    /** 与 {@code VersionedV6Writer#norm}/{@code DeletedRowKeys#canon} 同口径：数字 stripTrailingZeros；null→"∅"。 */
    private static String norm(Object v) {
        if (v == null) return "∅";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }
}
