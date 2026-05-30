package com.cpq.configure.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 加产品整份快照 — Phase 1（docs/方案-加产品整份快照.md）。
 *
 * <p>在 {@code ConfigureProductService.configure()} <b>提交之后</b>由 Resource 调用,把每个报价行
 * 各组件的整行展开值(ExpandDriverResponse.rows = [{driverRow, basicDataValues}])冻进
 * {@code quotation_line_component_data.snapshot_rows}(基础冻结层,与编辑层 row_data 分开)。
 *
 * <p><b>事务设计(关键)</b>:
 * <ul>
 *   <li>本协调方法 {@link #snapshotLines} <b>不带事务</b> —— expand 与渲染端 batch-expand 一样在
 *       无事务上下文执行,内部坏路径(如 {@code mat_bom.length})只产出 #ERROR 值,
 *       <b>不会污染/中止</b>外层事务(避免连累 configure 主写入)。</li>
 *   <li>写入用 {@link #writeSnapshot}(REQUIRES_NEW)独立小事务,逐组件隔离;某组件失败不影响其它。</li>
 *   <li>必须在 configure 提交后调用,REQUIRES_NEW 才能读到已提交的 line_item/基础数据/工序。</li>
 * </ul>
 *
 * <p><b>Phase 1 加性纪律</b>:仅"写"快照,渲染链路不读 snapshot_rows(仍实时展开),对现有渲染零影响;
 * 全程降级,任何失败只记日志,绝不影响加产品响应。
 */
@ApplicationScoped
public class ConfigureSnapshotService {

    private static final Logger LOG = Logger.getLogger(ConfigureSnapshotService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    /** 自注入:用于触发 REQUIRES_NEW 拦截器(同 bean 内自调用不经代理则拦截器失效)。 */
    @Inject
    ConfigureSnapshotService self;

    public static class DriverComp {
        public UUID id;
        public String name;
        public String driverPath;
    }

    /** 组合父级的子件行(lineItemId + partNo),用于按子件聚合展开。 */
    public static class ChildLine {
        public UUID lineItemId;
        public String partNo;
    }

    /**
     * 重快照整张报价单的所有行(saveDraft 全量重建后调用:行已是新 UUID,需按新行重建快照)。
     * 直接查 quotation_line_item,自包含;UPSERT 保留编辑层 row_data。
     */
    public void snapshotQuotation(UUID quotationId) {
        if (quotationId == null) return;
        try {
            List<Map<String, Object>> lines = self.loadQuotationLines(quotationId);
            snapshotLines(quotationId, lines);
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 整单重快照失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadQuotationLines(UUID quotationId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, product_part_no_snapshot, composite_type " +
                "FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", quotationId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", r[0]);
            m.put("productPartNo", r[1]);
            m.put("compositeType", r[2]);
            out.add(m);
        }
        return out;
    }

    /**
     * 为 configure 返回的各报价行写整份快照。expand 无事务运行(错误隔离),写入 REQUIRES_NEW。
     * 全程 try/catch 降级,绝不抛出影响加产品。
     */
    public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems) {
        if (quotationId == null || lineItems == null || lineItems.isEmpty()) return;
        try {
            // 清 driver 进程缓存(30s TTL),保证快照冻结的是"当前"基础值而非缓存旧值
            // (尤其"从基础刷新"在基础变更后需读到最新)。
            componentDriverService.evictAll();
            UUID customerId = self.loadCustomerId(quotationId);
            List<DriverComp> comps = self.loadDriverComponents(quotationId);
            if (comps.isEmpty()) return;
            for (Map<String, Object> li : lineItems) {
                UUID lineItemId = asUuid(li.get("id"));
                String partNo = li.get("productPartNo") != null ? li.get("productPartNo").toString() : null;
                String compositeType = li.get("compositeType") != null ? li.get("compositeType").toString() : null;
                if (lineItemId == null || partNo == null || partNo.isBlank()) continue;
                boolean isComposite = "COMPOSITE".equals(compositeType);
                // 组合父级:解析子件行(优先 parent_line_item_id,缺失则回退 BOM ASSEMBLY 同单 PART 行)。
                // 组合数据全挂在子件维度(工序绑子件 lineItemId / 材质·元素绑子件 material_no),
                // 父级 partNo 查 composite_child_*_mirror 恒为 0 行,故按子件聚合展开。
                List<ChildLine> children = isComposite
                        ? self.resolveCompositeChildren(quotationId, lineItemId, partNo)
                        : java.util.List.of();
                for (DriverComp comp : comps) {
                    try {
                        // composite_child_*_mirror = "按子件 partNo 取叶子行"语义;$zcj_bom 等父级组件不在此列。
                        boolean aggregateChildren = isComposite
                                && comp.driverPath != null
                                && comp.driverPath.contains("composite_child_");
                        List<ExpandDriverResponse.Row> rows = new ArrayList<>();
                        if (aggregateChildren) {
                            // 逐子件展开(与子件 PART 行自身快照同一调用:partNo=子料号,lineItemId=子行,compositeType=PART)
                            // 再拼接,得到组合父级该 Tab 的整组行。
                            for (ChildLine ch : children) {
                                ExpandDriverResponse cexp = componentDriverService.expand(
                                        comp.id, customerId, ch.partNo, null, null, null, ch.lineItemId, "PART");
                                if (cexp != null && cexp.rows != null) rows.addAll(cexp.rows);
                            }
                        } else {
                            // 非组合,或组合父级语义组件($zcj_bom 子配件清单):按父级 partNo 原行为展开。
                            // expand 在无事务上下文执行（同 batch-expand）→ 内部坏路径不中止事务
                            ExpandDriverResponse exp = componentDriverService.expand(
                                    comp.id, customerId, partNo, null, null, null, lineItemId, compositeType);
                            if (exp != null && exp.rows != null) rows.addAll(exp.rows);
                        }
                        // 组合子件聚合为空 → 写 NULL(快照"未命中")让渲染回退实时,避免"空快照=空渲染"把页签冻死;
                        // 其余情形(含 SIMPLE/PART 的合法空结果)保持"空数组冻结"原语义不变。
                        String rowsJson = (rows.isEmpty() && aggregateChildren)
                                ? null
                                : MAPPER.writeValueAsString(rows);
                        self.writeSnapshot(lineItemId, comp.id, comp.name, rowsJson);
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] line=%s comp=%s 跳过: %s", lineItemId, comp.id, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 快照整体失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public UUID loadCustomerId(UUID quotationId) {
        List<Object> r = em.createNativeQuery("SELECT customer_id FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : UUID.fromString(r.get(0).toString());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<DriverComp> loadDriverComponents(UUID quotationId) {
        // 按 template_component 取 live driver 组件(live component id,expand 可直接加载)
        // driver_path 用于判定组合父级该"聚合子件"(composite_child_*_mirror)还是"父级展开"($zcj_bom 子配件清单)
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT c.id, c.name, c.data_driver_path FROM quotation q " +
                "JOIN template_component tc ON tc.template_id = q.customer_template_id " +
                "JOIN component c ON c.id = tc.component_id " +
                "WHERE q.id = :q AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("q", quotationId).getResultList();
        List<DriverComp> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            DriverComp dc = new DriverComp();
            dc.id = UUID.fromString(r[0].toString());
            dc.name = r[1] != null ? r[1].toString() : null;
            dc.driverPath = r[2] != null ? r[2].toString() : null;
            out.add(dc);
        }
        return out;
    }

    /**
     * 解析组合父级的子件行(REQUIRES_NEW,读已提交)。
     * <ol>
     *   <li>优先按 {@code parent_line_item_id = 父行} 关联(configure 当下 insertLineItem 已写 parentId);</li>
     *   <li>关联缺失(saveDraft 全量重建后 tempParentIndex 二阶段未接上 → parent_line_item_id 为 NULL)时,
     *       回退按 BOM:本报价单内 partNo 命中父级 {@code material_bom_item[characteristic='ASSEMBLY']}
     *       装配子料号的 PART 行(同单 → 天然按客户隔离,无需再过滤 customer_no)。</li>
     * </ol>
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<ChildLine> resolveCompositeChildren(UUID quotationId, UUID parentLineItemId, String parentPartNo) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, product_part_no_snapshot FROM quotation_line_item " +
                "WHERE parent_line_item_id = :pid AND composite_type = 'PART' " +
                "  AND product_part_no_snapshot IS NOT NULL")
                .setParameter("pid", parentLineItemId).getResultList();
        if (rows.isEmpty() && parentPartNo != null && !parentPartNo.isBlank()) {
            rows = em.createNativeQuery(
                    "SELECT child.id, child.product_part_no_snapshot FROM quotation_line_item child " +
                    "WHERE child.quotation_id = :q AND child.composite_type = 'PART' " +
                    "  AND child.product_part_no_snapshot IN (" +
                    "     SELECT mbi.component_no FROM material_bom_item mbi " +
                    "     WHERE mbi.system_type = 'QUOTE' AND mbi.material_no = :pp " +
                    "       AND mbi.characteristic = 'ASSEMBLY' AND mbi.component_no IS NOT NULL)")
                    .setParameter("q", quotationId).setParameter("pp", parentPartNo).getResultList();
        }
        List<ChildLine> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            ChildLine cl = new ChildLine();
            cl.lineItemId = UUID.fromString(r[0].toString());
            cl.partNo = r[1].toString();
            out.add(cl);
        }
        return out;
    }

    /**
     * 写一个组件的快照(REQUIRES_NEW 独立事务,逐组件隔离)。
     * <b>UPSERT 而非删插</b>:更新基础冻结层 snapshot_rows,<b>保留编辑层 row_data</b>
     * (saveDraft 已写的编辑/或用户编辑不被覆盖);无行则插入。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeSnapshot(UUID lineItemId, UUID componentId, String tabName, String rowsJson) {
        // rowsJson == null → snapshot_rows = NULL(快照"未命中",渲染回退实时);用 SQL 字面量 NULL,
        // 避免 native query 给 null 绑定推断不出类型。非 null 走 CAST(:rows AS jsonb)。
        String rowsExpr = rowsJson == null ? "NULL" : "CAST(:rows AS jsonb)";
        var update = em.createNativeQuery(
                "UPDATE quotation_line_component_data " +
                "SET snapshot_rows = " + rowsExpr + ", snapshot_at = NOW(), tab_name = COALESCE(tab_name, :tab) " +
                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("tab", tabName)
                .setParameter("lid", lineItemId).setParameter("cid", componentId);
        if (rowsJson != null) update.setParameter("rows", rowsJson);
        int updated = update.executeUpdate();
        if (updated == 0) {
            var insert = em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data " +
                    "(line_item_id, component_id, tab_name, snapshot_rows, snapshot_at) " +
                    "VALUES (:lid, :cid, :tab, " + rowsExpr + ", NOW())")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId)
                    .setParameter("tab", tabName);
            if (rowsJson != null) insert.setParameter("rows", rowsJson);
            insert.executeUpdate();
        }
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
