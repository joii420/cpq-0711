package com.cpq.quotation.service.backfill;

import com.cpq.quotation.dto.backfill.BackfillGroupDTO;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
import com.cpq.quotation.dto.backfill.BackfillProductDTO;
import com.cpq.quotation.dto.backfill.BackfillRowDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * task-0721 报价数据版本升级 · B6 + repair-0727 B4（修 D4 预览失真 + D5 预览无语义）—— 回填影响预览
 * + previewToken。
 *
 * <p>只读 dry-run：与 {@link QuoteBackfillService#execute} 共用同一个 {@link QuoteBackfillCollector}，
 * 不写库（backtask B6"性能：dry-run 与真回填共用同一收集逻辑，避免两套"）。
 *
 * <p><b>如实性（D4，repair-0727）</b>：collector 已改 patch 语义（基底行集 ⊕ 列级 patch ⊖ 墓碑 ⊕ 新增），
 * {@link QuoteBackfillPlan.GroupChange#rowChanges} 里出现的都是<b>真实差异</b>行（CHANGE 只在 patch
 * 造成实际值变化时才产出，NOOP 组已被收集器整组过滤不会到达这里）——本类不再需要像 task-0721 时代
 * 那样自己重新逐列 diff、也不再有"零差异组不展示"的过滤需求：{@link QuoteBackfillCollector#collect}
 * 产出的 {@code plan.groups} 本身就已是"确有变化"的组全集，直接渲染即可。
 *
 * <p><b>业务语义化（D5，repair-0727）</b>：按产品聚合（{@link #productNoOf}）+ 中文标签
 * （{@link BackfillLabelResolver}）+ 料号/客户号批量解析品名/客户名（AC-R8 禁 N+1）。
 *
 * <p><b>previewToken 确定性</b>（需求说明 §12 Q4）：token 是"报价单当前有效状态"的纯函数——
 * 固定排序（表名 → groupKey 规范串 → op → 行身份）+ 数值归一（{@code stripTrailingZeros}，
 * 对齐 {@code VersionedV6Writer#norm} 的比对口径）+ NULL 稳定序列化（{@code "∅"}）。
 * 同一未变状态两次 {@link #preview} 必须得到同一 token；409 只应在预览与提交之间数据真的变了时触发。
 * <b>算法本身 repair-0727 不动</b>（api.md §2：部署瞬间在途 token 会 409，属预期，写进发布说明）。
 */
@ApplicationScoped
public class QuoteBackfillPreviewService {

    private static final Logger LOG = Logger.getLogger(QuoteBackfillPreviewService.class);

    @Inject QuoteBackfillCollector collector;
    @Inject BackfillLabelResolver labelResolver;
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

        // ── repair-0727 B4：批量收集料号/客户号，一次性解析品名/客户名（AC-R8 禁 N+1）──
        Set<String> materialNos = new LinkedHashSet<>();
        Set<String> customerNos = new LinkedHashSet<>();
        for (QuoteBackfillPlan.GroupChange g : sorted) {
            String productNo = QuoteTableAxis.productNoOf(g.table, g.groupKeyAxis);
            if (productNo != null) materialNos.add(productNo);
            Object cn = g.groupKeyAxis.get("customer_no");
            if (cn != null) customerNos.add(String.valueOf(cn));
            for (QuoteBackfillPlan.RowChange rc : g.rowChanges) {
                Object compNo = rowIdentity(rc).get("component_no");
                if (compNo != null) materialNos.add(String.valueOf(compNo));
            }
        }
        Map<String, String> materialNames = labelResolver.resolveMaterialNames(materialNos);
        Map<String, String> customerNames = labelResolver.resolveCustomerNames(customerNos);

        Map<String, BackfillProductDTO> productByKey = new LinkedHashMap<>();
        Set<String> affectedProducts = new LinkedHashSet<>();

        for (QuoteBackfillPlan.GroupChange g : sorted) {
            // repair-0727 B4：defense-in-depth——不变式已在 QuoteBackfillCollector.determineRoute 硬校验
            // （抛异常），这里只是第二道防线，出现即记 ERROR 但不中断预览渲染。
            if (g.route == QuoteBackfillPlan.Route.REBUILD && g.rowChanges.isEmpty()) {
                LOG.errorf("[quote-backfill-preview] 不变式违反：route=REBUILD 但 rowChanges 为空，" +
                    "table=%s axis=%s（repair-0727 裁决①，理论不该出现，请排查 collector）", g.table, g.groupKeyAxis);
            }

            BackfillGroupDTO gd = new BackfillGroupDTO();
            gd.table = g.table;
            gd.tabName = g.tabName;
            gd.groupKey = g.groupKeyAxis;
            gd.isGlobalShared = g.isGlobalShared;
            gd.versionFrom = g.versionFrom;
            gd.versionTo = computeVersionTo(g);
            gd.route = g.route.name();
            gd.baseSource = g.baseSource;
            gd.baseRowCount = g.baseRows.size();
            gd.resultRowCount = g.effectiveNewRows.size();
            gd.categoryLabel = labelResolver.categoryLabel(g.table);

            String productNo = QuoteTableAxis.productNoOf(g.table, g.groupKeyAxis);
            gd.productNo = productNo;
            gd.productName = productNo == null ? null : materialNames.get(productNo);
            gd.axisLabels = buildAxisLabels(g, materialNames, customerNames);

            for (QuoteBackfillPlan.RowChange rc : g.rowChanges) {
                BackfillRowDTO rd = new BackfillRowDTO();
                rd.op = rc.op;
                rd.v6Id = rc.v6Id;
                rd.conflict = rc.conflict;
                rd.rowLabel = labelResolver.rowLabel(g.table, rowIdentity(rc), materialNames);

                if ("CHANGE".equals(rc.op)) {
                    for (Map.Entry<String, Object> e : rc.newValues.entrySet()) {
                        BackfillRowDTO.ChangeEntry ce = new BackfillRowDTO.ChangeEntry();
                        ce.column = e.getKey();
                        ce.label = labelResolver.columnLabel(e.getKey(), g.columnAliases);
                        ce.oldValue = rc.oldValues.get(e.getKey());
                        ce.newValue = e.getValue();
                        rd.changes.add(ce);
                    }
                } else {
                    Map<String, Object> src = "ADD".equals(rc.op) ? rc.newValues : rc.oldValues;
                    for (Map.Entry<String, Object> e : src.entrySet()) {
                        BackfillRowDTO.ValueEntry ve = new BackfillRowDTO.ValueEntry();
                        ve.column = e.getKey();
                        ve.label = labelResolver.columnLabel(e.getKey(), g.columnAliases);
                        ve.value = e.getValue();
                        rd.values.add(ve);
                    }
                }
                gd.rows.add(rd);
                switch (rc.op) {
                    case "ADD" -> dto.summary.addedRows++;
                    case "DELETE" -> dto.summary.deletedRows++;
                    case "CHANGE" -> dto.summary.changedRows++;
                }
            }

            dto.summary.versionedGroups++;
            dto.groups.add(gd);
            int idx = dto.groups.size() - 1;

            if (productNo != null) {
                String custNo = g.groupKeyAxis.get("customer_no") == null ? null
                    : String.valueOf(g.groupKeyAxis.get("customer_no"));
                String key = productNo + "|" + custNo;
                BackfillProductDTO pd = productByKey.computeIfAbsent(key, k -> {
                    BackfillProductDTO p = new BackfillProductDTO();
                    p.productNo = productNo;
                    p.productName = materialNames.get(productNo);
                    p.customerNo = custNo;
                    p.customerName = custNo == null ? null : customerNames.get(custNo);
                    dto.products.add(p);
                    return p;
                });
                pd.groupIndexes.add(idx);
                affectedProducts.add(productNo);
            } else {
                dto.globalShared.groupIndexes.add(idx);
            }
        }
        dto.summary.affectedProducts = affectedProducts.size();
        dto.previewToken = computeToken(plan);
        return dto;
    }

    /** CHANGE 取 oldValues(基底)⊕newValues(diff) 合并后的"当前身份"；ADD 取 newValues；DELETE 取 oldValues。 */
    private static Map<String, Object> rowIdentity(QuoteBackfillPlan.RowChange rc) {
        if ("ADD".equals(rc.op)) return rc.newValues;
        if ("DELETE".equals(rc.op)) return rc.oldValues;
        Map<String, Object> merged = new LinkedHashMap<>(rc.oldValues);
        merged.putAll(rc.newValues);
        return merged;
    }

    private List<BackfillGroupDTO.AxisLabel> buildAxisLabels(QuoteBackfillPlan.GroupChange g,
            Map<String, String> materialNames, Map<String, String> customerNames) {
        List<BackfillGroupDTO.AxisLabel> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : g.groupKeyAxis.entrySet()) {
            String col = e.getKey();
            Object val = e.getValue();
            BackfillGroupDTO.AxisLabel al = new BackfillGroupDTO.AxisLabel();
            al.column = col;
            al.value = val;
            al.label = labelResolver.columnLabel(col, g.columnAliases);
            String valStr = val == null ? null : String.valueOf(val);
            if (valStr == null) {
                al.display = "—";
            } else if ("customer_no".equals(col)) {
                String name = customerNames.get(valStr);
                al.display = name != null ? name + "（" + valStr + "）" : valStr;
            } else if (isMaterialAxisColumn(col)) {
                String name = materialNames.get(valStr);
                al.display = name != null ? valStr + " " + name : valStr;
            } else {
                al.display = valStr;
            }
            out.add(al);
        }
        return out;
    }

    private static boolean isMaterialAxisColumn(String col) {
        return "material_no".equals(col) || "finished_material_no".equals(col)
            || "code".equals(col) || "material_part_no".equals(col);
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

    // ── previewToken 计算：固定排序 + 数值归一 + NULL 稳定序列化，SHA-256（repair-0727 不动算法本身）──

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
        // repair-0726 B6：material_master 的 pending 料号行 + 新料号 stub 也纳入 token（影响回填
        // 结果的一部分状态）。数据源已从暂存表迁移到 material_master.pending_quotation_id 行
        // （见 QuoteBackfillCollector#collect → listPending），此处仅是本地变量/注释措辞跟进。
        List<String> pendingMaterialKeys = new ArrayList<>();
        for (var s : plan.materialMasterPending) pendingMaterialKeys.add(canonStaged(s));
        Collections.sort(pendingMaterialKeys);
        // ⚠️ token 标签 "#staging=" 刻意不随字段/变量改名同步更新：token 是已发布给前端的预览凭证，
        // 改标签文本会让所有在途 preview token 与重算结果不一致 → 部署后用户手上的旧 token 提交时
        // 全部误报 409。不要"顺手"把它改成 "#pending=" 之类看起来更一致的名字。
        sb.append("#staging=").append(String.join(",", pendingMaterialKeys));
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
