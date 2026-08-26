package com.cpq.configure.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.QuotePendingScope;
import com.cpq.datasource.sqlview.TemplateRenderScope;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.cpq.quotation.service.BomNodeTypeResolver;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.CrossTabComponentOrder;
import com.cpq.quotation.service.RowDataMaterializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final ObjectMapper MAPPER = com.cpq.common.DecimalJacksonCustomizer.newMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    RowDataMaterializer rowDataMaterializer;

    /** task-0721 B3：报价侧 BOM 树整单渲染引擎（usage=QUOTE）。 */
    @Inject
    BomTreeRenderService bomTreeRenderService;

    /** task-0721 B5：树节点类型判定服务。 */
    @Inject
    BomNodeTypeResolver bomNodeTypeResolver;

    /** task-0721（2026-07-21 补录）：按 partNoField 显式解析料号列，供类型判定命中收集用。 */
    @Inject
    com.cpq.quotation.service.FormulaCalculator formulaCalculator;

    /** 自注入:用于触发 REQUIRES_NEW 拦截器(同 bean 内自调用不经代理则拦截器失效)。 */
    @Inject
    ConfigureSnapshotService self;

    /** task-0806 B8：driver 组件清单改问冻结快照，不再直读活 component 表。 */
    @Inject
    com.cpq.template.service.PublishedTemplateReader publishedTemplateReader;

    public static class DriverComp {
        public UUID id;
        public String name;
        public String driverPath;
        /** task-0721 B4：页签类型属性（BOM/材质元素/零件/外购件/主件/null）。 */
        public String tabType;
        /** task-0721（2026-07-21 补录）：该页签「料号列」字段名（tabType=BOM 可为 null）。 */
        public String partNoField;
        /** task-0721（2026-07-23 补录，匹配标识放宽）：该页签「名称列」字段名——partNoField 为空时的
         * 兜底标识列（如「外购件/费用」类页签无料号列，只用「料件名称」做标识）。 */
        public String partNameField;
        /** 该组件 fields JSON（供 partNoField/partNameField 解析用，FormulaCalculator.computeRowKey 需要）。 */
        public String fields;
        /** task-0722：多行页签「行排序列」字段名（可空，快照按其数字感知升序排列）。 */
        public String sortField;
    }

    /**
     * 批量写快照的行载体（整行 M 个组件打包一个事务）。
     */
    public static class SnapRow {
        public final UUID componentId;
        public final String tabName;
        /** null → snapshot_rows 落 NULL jsonb；非 null → CAST(:rows AS jsonb)。 */
        public final String rowsJson;

        public SnapRow(UUID componentId, String tabName, String rowsJson) {
            this.componentId = componentId;
            this.tabName = tabName;
            this.rowsJson = rowsJson;
        }
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
        snapshotQuotation(quotationId, false);
    }

    /**
     * @param skipRowsWithSnapshot true（saveDraft 高频路径）：某行所有 driver 组件都已有
     *        snapshot_rows 即整行跳过 expand；false（强制刷新/加产品）：行为同改造前。
     */
    public void snapshotQuotation(UUID quotationId, boolean skipRowsWithSnapshot) {
        if (quotationId == null) return;
        try {
            List<Map<String, Object>> lines = self.loadQuotationLines(quotationId);
            snapshotLines(quotationId, lines, skipRowsWithSnapshot);
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 整单重快照失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    /**
     * Part B 跳过判定：给定本行的 driver 组件集合与各组件现有 snapshot_rows，
     * 判断是否仍需重 expand。任一 driver 组件缺 snapshot_rows（不在 map 或值为 null）→ 需 expand。
     * 合法的 0 行组件其值为 "[]"（非 null）视为已快照、可跳过。
     */
    public static boolean lineNeedsExpand(java.util.Collection<UUID> driverCompIds,
                                          Map<UUID, String> snapshotByComp) {
        if (driverCompIds == null || driverCompIds.isEmpty()) return false;
        for (UUID cid : driverCompIds) {
            String sr = snapshotByComp == null ? null : snapshotByComp.get(cid);
            if (sr == null) return true;   // 缺键或 null 值 → 需 expand
        }
        return false;
    }

    /** 读某行各组件现有 snapshot_rows（componentId → snapshot_rows，可能 null 值）。Part B 跳过判定用。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, String> loadSnapshotRowsByComp(UUID lineItemId) {
        Map<UUID, String> out = new HashMap<>();
        if (lineItemId == null) return out;
        List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id, snapshot_rows FROM quotation_line_component_data WHERE line_item_id = :li")
                .setParameter("li", lineItemId).getResultList();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            out.put(UUID.fromString(r[0].toString()), r[1] == null ? null : r[1].toString());
        }
        return out;
    }

    /**
     * Phase 1 改造点②：整单一次查所有行的 snapshot_rows。
     *
     * <p>现状 {@link #loadSnapshotRowsByComp} 每行调一次（saveDraft 增量路径 N 次往返）；
     * 本方法改为一条 {@code WHERE line_item_id IN (:lis)} 一次取全，循环内查内存 map，
     * 从 N 次往返降到 1 次。
     *
     * <p>空集合入参 → 直接返空 map（避免 SQL {@code IN ()} 语法错）。
     *
     * @param lineItemIds 本次报价单全部 line_item_id
     * @return 外层 lineItemId → 内层 componentId → snapshot_rows（值可为 null，保留 null 语义）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<UUID, Map<UUID, String>> loadSnapshotRowsByLines(java.util.Collection<UUID> lineItemIds) {
        Map<UUID, Map<UUID, String>> out = new HashMap<>();
        if (lineItemIds == null || lineItemIds.isEmpty()) return out;
        List<Object[]> rows = em.createNativeQuery(
                "SELECT line_item_id, component_id, snapshot_rows " +
                "FROM quotation_line_component_data " +
                "WHERE line_item_id IN (:lis)")
                .setParameter("lis", new ArrayList<>(lineItemIds))
                .getResultList();
        for (Object[] r : rows) {
            if (r[0] == null) continue;  // line_item_id 不应为 null，但防御
            UUID lid = UUID.fromString(r[0].toString());
            if (r[1] == null) continue;  // component_id 为 null 的行跳过（与 loadSnapshotRowsByComp 同）
            UUID cid = UUID.fromString(r[1].toString());
            String snapshotRows = r[2] == null ? null : r[2].toString();
            out.computeIfAbsent(lid, k -> new HashMap<>()).put(cid, snapshotRows);
        }
        return out;
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
        snapshotLines(quotationId, lineItems, false);
    }

    /**
     * @param skipRowsWithSnapshot true（saveDraft 增量）：复用行已有完整 snapshot_rows 时整行跳过
     *        expand + materialize；false（加产品只传新行 / 强制刷新）：行为同改造前。
     */
    public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems,
                              boolean skipRowsWithSnapshot) {
        if (quotationId == null || lineItems == null || lineItems.isEmpty()) return;
        // Phase 1 kill switch: cpq.firstsave-batch-write（默认 true）
        // kill: -Dcpq.firstsave-batch-write=false 或 export CPQ_FIRSTSAVE_BATCH_WRITE=false
        boolean batchWriteEnabled = "true".equalsIgnoreCase(
                System.getProperty("cpq.firstsave-batch-write",
                    System.getenv().getOrDefault("CPQ_FIRSTSAVE_BATCH_WRITE", "true")));
        // Phase 2 kill switch: cpq.firstsave-quote-bucket（默认 true，2026-06-24 灰度通过后开启）
        // 等价已铁证：罗克韦尔 8f0c37a4(170 行/含重复料号) 新旧路径 md5 全等 + 连跑两次确定 + 108s→8.6s。
        // kill: -Dcpq.firstsave-quote-bucket=false 或 export CPQ_FIRSTSAVE_QUOTE_BUCKET=false
        boolean quoteBucketEnabled = "true".equalsIgnoreCase(
                System.getProperty("cpq.firstsave-quote-bucket",
                    System.getenv().getOrDefault("CPQ_FIRSTSAVE_QUOTE_BUCKET", "true")));
        // Phase 2 落库批量 kill switch: cpq.firstsave-whole-batch（默认 true）——snapshot_rows 整单一次写
        // (替代每行 writeSnapshotBatch 的 N×REQUIRES_NEW)。仅 batchWriteEnabled 时生效。
        boolean wholeBatchEnabled = batchWriteEnabled && "true".equalsIgnoreCase(
                System.getProperty("cpq.firstsave-whole-batch",
                    System.getenv().getOrDefault("CPQ_FIRSTSAVE_WHOLE_BATCH", "true")));
        // 整单收集各行 SnapRow / row_data,循环末各一次 writeSnapshotBatchAllLines / writeRowDataBatchAllLines。
        Map<UUID, List<SnapRow>> allSnapRows = wholeBatchEnabled ? new LinkedHashMap<>() : null;
        Map<UUID, Map<UUID, ArrayNode>> allRowData = wholeBatchEnabled ? new LinkedHashMap<>() : null;
        try {
            // 注:evictAll 改为「懒触发」——仅当确有行需要 expand 时才清缓存 + 合桶预取(见下方 anyNeedsExpand 闸门),
            // 否则增量 draft(全行已有快照→全跳过)会白白 evictAll + 报价合桶 expand(纯浪费)。
            UUID customerId = self.loadCustomerId(quotationId);
            List<DriverComp> comps = self.loadDriverComponents(quotationId);
            if (comps.isEmpty()) return;
            // Part B: driver 组件 id 集合，用于复用行"已有完整 snapshot_rows → 跳过"判定
            java.util.List<UUID> driverCompIds = new java.util.ArrayList<>();
            for (DriverComp dc : comps) driverCompIds.add(dc.id);
            // 统一协议(2026-05-30):所有 (line × component) 走同一路径,SQL 视图自己用
                //   :quotationId + :customerCode + 外层 :hfPartNos
                // 自适应 SIMPLE / COMPOSITE 语义(视图内 UNION ALL),Java 不再按 driverPath 判定聚合。
            QuotationIdContext.set(quotationId);
            // task-0725 T3-P1：报价侧 pending 可见域主战场。覆盖建单/加产品/saveDraft/从基础刷新/报价树
            // （render(…,"QUOTE") 在 :350-351，位于本 try 块内）。status 从 quotation 表取，quotationId
            // 未知报价单/冻结态时 open() 内建判定存 null，与修复前逐位相同（AC-10）。
            // 🔴 AC-17：本方法只服务报价侧（customer_template_id 驱动），核价侧走 CardSnapshotService 的
            // 独立方法，不共用本 open()。
            String _status = self.loadQuotationStatus(quotationId);
            UUID _pqPrev = QuotePendingScope.open(quotationId, _status);
            // task-0806 B17-a：模板渲染域，覆盖本方法内全部 componentDriverService.expand/expandMulti
            // 调用点（下方直接 expand 兜底 + precomputeQuoteDriverBuckets 内 expandMulti），让
            // ComponentDriverService.setNested 能拿到真实 templateId（原恒传 null）。与 :365 下方
            // treeComps 分支各自查询 loadCustomerTemplateId 的旧写法合并为同一次取值，见该处改动。
            UUID _customerTemplateId = self.loadCustomerTemplateId(quotationId);
            UUID _tplPrev = TemplateRenderScope.open(_customerTemplateId);
            try {
                // 物化所需:模板 components_snapshot(含各 tab 的 componentCode/fields/formulas)。一次加载,逐行复用。
                JsonNode componentsSnapshot = self.loadComponentsSnapshot(quotationId);

                // Phase 1 改造点②：整单一次查 snapshot_rows（kill switch on → 1 次；off → 循环内逐行查）
                // byLine: lineItemId → (componentId → snapshot_rows)；循环内读内存 map 替代逐行 DB 查。
                Map<UUID, Map<UUID, String>> byLine;
                if (skipRowsWithSnapshot && batchWriteEnabled) {
                    // 收集全部 lineItemId，整单一次 IN 查
                    java.util.List<UUID> allLineIds = new java.util.ArrayList<>();
                    for (Map<String, Object> li : lineItems) {
                        UUID lid = asUuid(li.get("id"));
                        if (lid != null) allLineIds.add(lid);
                    }
                    byLine = self.loadSnapshotRowsByLines(allLineIds);
                } else {
                    byLine = null; // null → 循环内回退逐行查（kill switch off 或非增量路径）
                }

                // 懒触发闸门:仅当确有行需要 expand 时才 evictAll + 合桶预取。
                // 增量 draft(skipRowsWithSnapshot=true)且全行已有完整 snapshot_rows → 全部跳过 → 无需 expand
                // → 省掉 evictAll + precomputeQuoteDriverBuckets(报价 driver 整单 expandMulti)这块纯浪费。
                boolean anyNeedsExpand;
                if (skipRowsWithSnapshot && byLine != null) {
                    anyNeedsExpand = false;
                    for (Map<String, Object> li : lineItems) {
                        UUID lid = asUuid(li.get("id"));
                        if (lid == null) continue;
                        if (lineNeedsExpand(driverCompIds, byLine.getOrDefault(lid, java.util.Map.of()))) {
                            anyNeedsExpand = true; break;
                        }
                    }
                } else {
                    anyNeedsExpand = true;   // 非增量(强制刷新)或无 byLine → 全行 expand,保原行为
                }

                // repair-0729 Task2（空覆盖护栏，AP-60 同族不变量）：整单一次预取现有 snapshot_rows
                // （componentId 维度），供下方两处真正写入点判断"待写空值 + 库内现值非空"时跳过覆盖。
                // 优先复用刚算好的 byLine（saveDraft 增量热路径零额外开销）；byLine==null（加产品/强制
                // 刷新非增量路径）且确有行需要 expand 时单独查一次（整单 1 次 IN 查，非热路径可接受）；
                // anyNeedsExpand=false（全行跳过 expand）时不查，省一次往返。
                Map<UUID, Map<UUID, String>> existingSnapshotByLine;
                if (byLine != null) {
                    existingSnapshotByLine = byLine;
                } else if (anyNeedsExpand) {
                    java.util.List<UUID> allLineIdsForGuard = new java.util.ArrayList<>();
                    for (Map<String, Object> li : lineItems) {
                        UUID lid = asUuid(li.get("id"));
                        if (lid != null) allLineIdsForGuard.add(lid);
                    }
                    existingSnapshotByLine = self.loadSnapshotRowsByLines(allLineIdsForGuard);
                } else {
                    existingSnapshotByLine = Map.of();
                }

                // 清 driver 进程缓存(30s TTL),保冷跑语义——仅在确需 expand 时(否则跳过这次 evict)。
                if (anyNeedsExpand) componentDriverService.evictAll();

                // task-260819 B-19/B-21（D-52/D-56/AC-58/AC-62）：整单一次算好 BomTreeVarsContext
                // 所需的两样东西——供下方「合桶预取」与「Pass1 逐行 fallback expand」两段各自局部
                // 注入。提前到此处（而不是留在原 :376-389 只在 treeComps 非空时才建的分支里）：
                //   ① liteLines 无条件构造，treeComps 分支复用同一份（原地各建一次的重复消除）；
                //   ② collectTotalMaterialNoUnion 只在 anyNeedsExpand 时算——不需要 expand 就不必
                //      为它多发一次递归 SQL（与 buckets/evictAll 同一懒触发闸门，N+1 约束①：
                //      本次快照操作整单只发这一次递归 SQL，与行数/组件数无关）。
                // ⚠️ 两段局部作用域各自 set/finally-clear，不与中间 :372-401 bomTreeRenderService.render()
                //   的内部 set/clear 共享一个 try 块——BomTreeVarsContext.clear() 是无条件 TL.remove()，
                //   一个大 try 会被 render() 内部提前清空（详见 B-19 方案报告）。
                List<QuotationLineItem> liteLines = new ArrayList<>();
                for (Map<String, Object> li : lineItems) {
                    UUID lid = asUuid(li.get("id"));
                    Object pnObj = li.get("productPartNo");
                    String pn = pnObj != null ? pnObj.toString() : null;
                    if (lid == null || pn == null || pn.isBlank()) continue;
                    QuotationLineItem lite = new QuotationLineItem();
                    lite.id = lid;
                    lite.productPartNoSnapshot = pn;
                    liteLines.add(lite);
                }
                BomTreeRenderService.MaterialUnionResult quoteUnion = null;
                if (anyNeedsExpand && !liteLines.isEmpty()) {
                    quoteUnion = bomTreeRenderService.collectTotalMaterialNoUnion(liteLines, "QUOTE");
                }

                // Phase 2 改造点：整单合桶预取（evictAll 之后,保冷跑语义）。仅 anyNeedsExpand 时算。
                // buckets: componentId → (partNo → ExpandDriverResponse)；不 eligible 组件不进(逐行回落)。
                Map<UUID, Map<String, ExpandDriverResponse>> buckets;
                if (quoteBucketEnabled && anyNeedsExpand) {
                    if (quoteUnion != null) {
                        com.cpq.datasource.sqlview.BomTreeVarsContext.set(new com.cpq.datasource.sqlview.BomTreeVarsContext.Vars(
                                null, quoteUnion.totalMaterialNo, null,
                                com.cpq.datasource.sqlview.BomTreeVarsContext.Mode.RENDER, quoteUnion.rootsByMaterial,
                                quoteUnion.materialsByRoot));
                    }
                    try {
                        buckets = precomputeQuoteDriverBuckets(quotationId, customerId, comps, lineItems);
                    } finally {
                        if (quoteUnion != null) com.cpq.datasource.sqlview.BomTreeVarsContext.clear();
                    }
                } else {
                    buckets = Map.of();
                }

                // task-0721 B3：树页签(tab_type='BOM') → 整单一次调 BomTreeRenderService.render(usage=QUOTE)，
                // 逐 line 复用其 spine + 系统列结果（treeBaseRowsByLine.get(lineItemId).get(compIdStr)）。
                // 单一路由收口点：BomTreeRenderService.isQuoteTreeTabType（判据 tab_type='BOM'，
                // 与 bomRecursiveExpand 解耦——2026-07-21 裁决 Q1）。不含树页签的模板 treeComps 恒空，
                // 下方 getOrDefault 恒查不到 → 逐行走既有平铺路径，零改动零回归。
                List<DriverComp> treeComps = new ArrayList<>();
                for (DriverComp dc : comps) {
                    if (BomTreeRenderService.isQuoteTreeTabType(dc.tabType)) treeComps.add(dc);
                }
                Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine = java.util.Collections.emptyMap();
                // BL-0030 同款失败哨兵：render 异常不上抛(否则整单快照失败 → 前端无限"加载中…")，
                // 落带原文的失败哨兵行，仅影响树页签本身；同行其它(非树)组件独立展开，不受牵连。
                String treeRenderError = null;
                if (!treeComps.isEmpty() && anyNeedsExpand) {
                    // task-0806 B17-a：复用外层已 hoist 的 _customerTemplateId，省一次 REQUIRES_NEW 查询
                    // （原为独立 self.loadCustomerTemplateId(quotationId) 调用，同一值，改为直接引用）。
                    UUID customerTemplateId = _customerTemplateId;
                    if (customerTemplateId != null) {
                        // task-260819 B-19：liteLines 已在上方（buckets 计算前）无条件构造一份，
                        // 此处直接复用，不再重复构造（原地各建一次的重复消除）。
                        if (!liteLines.isEmpty()) {
                            try {
                                treeBaseRowsByLine = bomTreeRenderService.render(
                                        customerTemplateId, liteLines, null, "QUOTE");
                            } catch (Exception e) {
                                treeRenderError = e.getMessage();
                                LOG.errorf("[add-snapshot] quotation=%s 报价树整单渲染失败 → 落失败哨兵透出前端: %s",
                                        quotationId, e.getMessage());
                            }
                        }
                    }
                }

                // task-260819 B-19：Pass1 逐行 fallback expand（下方 componentDriverService.expand，
                // buckets 未命中时的回落路径）同样依赖 :total_material_no——独立于上面「合桶预取」
                // 那段作用域（中间隔着 :396-427 的 bomTreeRenderService.render() 调用，其内部会
                // 无条件 clear() 掉线程上的 BomTreeVarsContext，不能跨它共用一个 try 块，见上方注释）。
                if (quoteUnion != null) {
                    com.cpq.datasource.sqlview.BomTreeVarsContext.set(new com.cpq.datasource.sqlview.BomTreeVarsContext.Vars(
                            null, quoteUnion.totalMaterialNo, null,
                            com.cpq.datasource.sqlview.BomTreeVarsContext.Mode.RENDER, quoteUnion.rootsByMaterial,
                            quoteUnion.materialsByRoot));
                }
                try {
                for (Map<String, Object> li : lineItems) {
                    UUID lineItemId = asUuid(li.get("id"));
                    String partNo = li.get("productPartNo") != null ? li.get("productPartNo").toString() : null;
                    String compositeType = li.get("compositeType") != null ? li.get("compositeType").toString() : null;
                    if (lineItemId == null || partNo == null || partNo.isBlank()) continue;
                    // Part B: 复用行所有 driver 组件已有 snapshot_rows → 整行跳过 expand + materialize（增量）
                    if (skipRowsWithSnapshot) {
                        // 读内存 map（整单已预取，kill switch on）或逐行 DB 查（kill switch off）
                        Map<UUID, String> snapshotByComp = (byLine != null)
                                ? byLine.getOrDefault(lineItemId, Map.of())
                                : self.loadSnapshotRowsByComp(lineItemId);
                        if (!lineNeedsExpand(driverCompIds, snapshotByComp)) {
                            LOG.debugf("[add-snapshot] line=%s 已有完整 snapshot_rows, 跳过重 expand(增量)", lineItemId);
                            continue;
                        }
                    }
                    // 本行各组件刚写入的 snapshot_rows JSON(componentId → rowsJson),供随后物化 row_data 复用。
                    Map<UUID, String> snapByComp = new LinkedHashMap<>();
                    // Phase 1: 收集整行 SnapRow，循环末一次批量写（ON=batch，OFF=逐行）
                    List<SnapRow> snapRowBatch = batchWriteEnabled ? new ArrayList<>() : null;
                    // task-0721 B5：本行的页签命中上下文(仅含树页签时才建,纯逻辑对象,建了也不影响非树行为)。
                    BomNodeTypeResolver.TabHitContext treeTypeCtx =
                            treeComps.isEmpty() ? null : new BomNodeTypeResolver.TabHitContext();

                    // Pass 1：既有平铺组件展开逻辑(逐位不变) —— 跳过树页签(tab_type='BOM'，Pass 2 单独处理)。
                    for (DriverComp comp : comps) {
                        if (BomTreeRenderService.isQuoteTreeTabType(comp.tabType)) continue;
                        try {
                            ExpandDriverResponse exp;
                            Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                            if (bucket != null) {
                                // Phase 2 合桶命中：按本行 partNo 取（expandMulti 已按 hf_part_no 回分）。
                                // 重复料号多行从同一 entry 取数 = 期望语义（同料号产品卡内容相同）。
                                // AP-37 可变共享面：exp.rows 是共享引用，写入前必须经 MAPPER.writeValueAsString 深拷贝。
                                exp = bucket.get(partNo);
                                if (exp == null) {
                                    // 防御：expandMulti 已预置全 partNo 空响应，理论上不会为 null
                                    exp = emptyExpandResponse();
                                }
                            } else {
                                // 不 eligible 或 flag off → 逐行回落（保 Bug B + lineItemId 隔离 + composite 语义）。
                                // 8-arg 签名 1:1 复刻现状，childLineItemIds 不传（null）。
                                exp = componentDriverService.expand(
                                        comp.id, customerId, partNo, null, null, null, lineItemId, compositeType);
                            }
                            List<ExpandDriverResponse.Row> rows = (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                            // task-0722：组件级 sort_field → 按该列对 driver 行数字感知升序排列(平铺页签)。
                            // 视图 ORDER BY 在报价单 pending 改写管线下会被丢弃(SELECT* 外壳无外层排序),故排序落此处。
                            // 返回排序后的新副本(AP-37：绝不原地改 exp.rows 共享缓存引用)。
                            rows = sortRowsBySortField(rows, comp);
                            // task-0721 B5：本组件若挂了「材质元素/零件/外购件/主件」类型属性，把本行渲染出的
                            // 标识值（按 comp.partNoField 显式取值，为空则回落 comp.partNameField——
                            // 2026-07-21 补录：禁止按字段名启发式猜测；2026-07-23 放宽：料号列优先，
                            // 名称列兜底，如「外购件/费用」类页签无料号列只用「料件名称」做标识）
                            // 并入本行的类型判定上下文(供 Pass 2 树页签 __nodeType 结构推导用)。
                            String identifierField = (comp.partNoField != null && !comp.partNoField.isBlank())
                                    ? comp.partNoField
                                    : comp.partNameField;
                            if (treeTypeCtx != null && comp.tabType != null && identifierField != null
                                    && !identifierField.isBlank()) {
                                JsonNode compFieldsNode = parseFieldsJsonSafe(comp.fields);
                                JsonNode rkf = MAPPER.createArrayNode().add(identifierField);
                                for (ExpandDriverResponse.Row row : rows) {
                                    if (row == null) continue;
                                    JsonNode driverRowNode = MAPPER.valueToTree(
                                            row.driverRow != null ? row.driverRow : Map.of());
                                    JsonNode basicDataNode = MAPPER.valueToTree(
                                            row.basicDataValues != null ? row.basicDataValues : Map.of());
                                    String mn = formulaCalculator.computeRowKey(rkf, compFieldsNode, driverRowNode, basicDataNode);
                                    if (mn != null && !mn.isBlank()) treeTypeCtx.addHit(comp.tabType, mn);
                                }
                            }
                            // writeValueAsString 序列化 = 深拷贝（AP-37 可变共享面保护：各行独立 JSON 字符串）
                            String rowsJson = MAPPER.writeValueAsString(rows);
                            // repair-0729 Task2（空覆盖护栏，AP-60 同族不变量）：待写值为空且库内现值
                            // 非空 → 跳过本组件的写入（不进批次/不调 writeSnapshot），保留库内旧值；
                            // snapByComp 也回填旧值，保证下游 row_data 物化与库内实际持久化一致。
                            String existingRowsJson = existingSnapshotByLine
                                    .getOrDefault(lineItemId, Map.of()).get(comp.id);
                            if (isEmptyRowsJson(rowsJson) && !isEmptyRowsJson(existingRowsJson)) {
                                LOG.warnf("[add-snapshot] EMPTY_OVERWRITE_BLOCKED line=%s comp=%s(%s): " +
                                        "重查得到空结果, 库内已有非空 snapshot_rows, 跳过覆盖写入(保留旧值)",
                                        lineItemId, comp.id, comp.name);
                                CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.incrementAndGet();
                                snapByComp.put(comp.id, existingRowsJson);
                            } else {
                                if (batchWriteEnabled) {
                                    // 收集到批次，循环结束后统一写
                                    snapRowBatch.add(new SnapRow(comp.id, comp.name, rowsJson));
                                } else {
                                    // kill switch OFF：保持原逐行写
                                    self.writeSnapshot(lineItemId, comp.id, comp.name, rowsJson);
                                }
                                snapByComp.put(comp.id, rowsJson);
                            }
                        } catch (Exception e) {
                            LOG.warnf("[add-snapshot] line=%s comp=%s 跳过: %s", lineItemId, comp.id, e.getMessage());
                        }
                    }

                    // Pass 2（task-0721 B3/B5）：树页签(tab_type='BOM') —— 用 BomTreeRenderService 整单渲染
                    // 好的 spine 结果(precomputed baseRows)，不再逐行 expand。__nodeType 在此一次算好写入
                    // （不留给前端算，backtask B3 要点）。先统一收集全部树组件的父子边（规则三结构推导用），
                    // 再逐组件注入 __nodeType，保证同行内多棵"树 Tab"共享同一份类型判定上下文。
                    if (!treeComps.isEmpty()) {
                        Map<String, ArrayNode> treeRowsByComp = new LinkedHashMap<>();
                        for (DriverComp comp : treeComps) {
                            ArrayNode rows;
                            if (treeRenderError != null) {
                                rows = buildTreeRenderErrorSentinel(treeRenderError);
                            } else {
                                ArrayNode precomputed = treeBaseRowsByLine
                                        .getOrDefault(lineItemId, Map.of())
                                        .get(comp.id.toString());
                                rows = precomputed != null ? precomputed : MAPPER.createArrayNode();
                                addChildEdgesFromTreeRows(rows, treeTypeCtx);
                            }
                            treeRowsByComp.put(comp.id.toString(), rows);
                        }
                        for (DriverComp comp : treeComps) {
                            try {
                                ArrayNode rows = treeRowsByComp.get(comp.id.toString());
                                if (treeRenderError == null) {
                                    injectNodeTypes(rows, treeTypeCtx);
                                }
                                String rowsJson = MAPPER.writeValueAsString(rows);
                                // repair-0729 Task2（空覆盖护栏，AP-60 同族不变量）：同 Pass 1，待写值为空
                                // 且库内现值非空 → 跳过写入保留旧值（树渲染失败已有独立哨兵 buildTreeRenderErrorSentinel
                                // 兜底，不是空数组，不会误触发本护栏）。
                                String existingRowsJson = existingSnapshotByLine
                                        .getOrDefault(lineItemId, Map.of()).get(comp.id);
                                if (isEmptyRowsJson(rowsJson) && !isEmptyRowsJson(existingRowsJson)) {
                                    LOG.warnf("[add-snapshot] EMPTY_OVERWRITE_BLOCKED line=%s tree-comp=%s(%s): " +
                                            "重查得到空结果, 库内已有非空 snapshot_rows, 跳过覆盖写入(保留旧值)",
                                            lineItemId, comp.id, comp.name);
                                    CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.incrementAndGet();
                                    snapByComp.put(comp.id, existingRowsJson);
                                } else {
                                    if (batchWriteEnabled) {
                                        snapRowBatch.add(new SnapRow(comp.id, comp.name, rowsJson));
                                    } else {
                                        self.writeSnapshot(lineItemId, comp.id, comp.name, rowsJson);
                                    }
                                    snapByComp.put(comp.id, rowsJson);
                                }
                            } catch (Exception e) {
                                LOG.warnf("[add-snapshot] line=%s tree-comp=%s 跳过: %s",
                                        lineItemId, comp.id, e.getMessage());
                            }
                        }
                    }
                    // Phase 1: 整行一次批量写（N×M×1 → N×1 REQUIRES_NEW）。
                    // Phase 2 落库批量: wholeBatchEnabled 时只收集,循环末整单一次 writeSnapshotBatchAllLines。
                    if (batchWriteEnabled && snapRowBatch != null && !snapRowBatch.isEmpty()) {
                        if (wholeBatchEnabled) {
                            allSnapRows.put(lineItemId, snapRowBatch);    // 延后整单写(内容等价:见 writeSnapshotBatchAllLines)
                        } else {
                            try {
                                self.writeSnapshotBatch(lineItemId, snapRowBatch);
                            } catch (Exception e) {
                                LOG.warnf("[add-snapshot] line=%s 批量写 snapshot 失败(已降级): %s",
                                        lineItemId, e.getMessage());
                            }
                        }
                    }
                    // 写时算齐:把 FORMULA 叶子列算进 row_data(扁平),让 Excel 视图无需用户编辑即正确求和。
                    // #2 物化批量: wholeBatchEnabled 时只算不写,循环末整单一次 writeRowDataBatchAllLines。
                    try {
                        if (wholeBatchEnabled) {
                            Map<UUID, ArrayNode> rd = computeRowDataFromSnap(lineItemId, componentsSnapshot, snapByComp);
                            if (rd != null && !rd.isEmpty()) allRowData.put(lineItemId, rd);
                        } else {
                            materializeRowData(lineItemId, componentsSnapshot, snapByComp, batchWriteEnabled);
                        }
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] line=%s 物化 row_data 失败(已降级,仍可编辑后修正): %s",
                                lineItemId, e.getMessage());
                    }
                }
                } finally {
                    // task-260819 B-19：与本段开头的 set 成对——BomTreeVarsContext.clear() 是无条件
                    // TL.remove()，渲染结束必须 remove，防串单（ThreadLocal 泄漏会把本单的料号并集
                    // 漏给下一次复用同线程的操作）。
                    if (quoteUnion != null) com.cpq.datasource.sqlview.BomTreeVarsContext.clear();
                }
                // Phase 2 落库批量: 整单一次写全部行 snapshot_rows(替代每行 writeSnapshotBatch)。
                // 顺序:先 snapshot(建行 + snapshot_rows),后 row_data(UPDATE 命中);最终
                //   (snapshot_rows,row_data,tab_name) 与逐行序逐位一致(仅 NOW() 时间戳差,非渲染/golden 维度)。
                if (wholeBatchEnabled && allSnapRows != null && !allSnapRows.isEmpty()) {
                    try {
                        self.writeSnapshotBatchAllLines(allSnapRows);
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] quotation=%s 整单批量写 snapshot 失败(已降级): %s",
                                quotationId, e.getMessage());
                    }
                }
                // #2 物化批量: 整单一次写全部行 row_data(替代每行 writeRowDataBatch)。
                if (wholeBatchEnabled && allRowData != null && !allRowData.isEmpty()) {
                    try {
                        self.writeRowDataBatchAllLines(allRowData);
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] quotation=%s 整单批量写 row_data 失败(已降级): %s",
                                quotationId, e.getMessage());
                    }
                }
            } finally {
                TemplateRenderScope.restore(_tplPrev);
                QuotePendingScope.restore(_pqPrev);
                QuotationIdContext.clear();
            }
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 快照整体失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    /**
     * Phase 2 — 报价侧整单合桶预取（P3-C1）。
     *
     * <p>对每个 {@link com.cpq.component.service.ComponentDriverService#eligibleForQuoteBucket eligible}
     * 组件，一次 {@code expandMulti(全部不同 partNo)} 取回整单所有料号的行，按 {@code hf_part_no} 回分；
     * 不 eligible 的组件不进返回 Map → 调用方据此逐行回落（保 Bug B + lineItemId 隔离）。
     *
     * <p><b>调用时机</b>：在 {@code evictAll()} 之后调用（保冷跑语义）。
     * {@code expandMulti} 不进 {@code expandCache}（:521 注释）→ 天然冷跑。
     *
     * <p><b>报价侧 vs 核价侧区别</b>：报价侧无 BOM 闭包，分桶键就是产品料号本身（不做 BOM 递归展开）。
     * 参照 {@code CardSnapshotService#precomputeCostingDriverUnion}（核价侧同套路）。
     *
     * <p><b>可变共享面（AP-37 硬约束）</b>：返回的 Map 中同一 partNo 的 resp 被多行共享；
     * 调用方每行写入前必须经 {@code MAPPER.writeValueAsString(exp.rows)}（深拷贝），不得就地 mutate。
     *
     * @param quotationId 报价单 id（用于日志）
     * @param customerId  客户 id（传入 expandMulti）
     * @param comps       driver 组件列表（loadDriverComponents 已取）
     * @param lineItems   报价行列表（取全部不同料号）
     * @return componentId → (partNo → ExpandDriverResponse)；仅含 eligible 组件
     */
    Map<UUID, Map<String, ExpandDriverResponse>> precomputeQuoteDriverBuckets(
            UUID quotationId, UUID customerId, List<DriverComp> comps, List<Map<String, Object>> lineItems) {
        Map<UUID, Map<String, ExpandDriverResponse>> result = new LinkedHashMap<>();
        if (comps == null || comps.isEmpty() || lineItems == null || lineItems.isEmpty()) return result;

        // 收集全部不同料号（LinkedHashSet 保插入序去重）
        LinkedHashSet<String> distinctPartNoSet = new LinkedHashSet<>();
        for (Map<String, Object> li : lineItems) {
            Object pn = li.get("productPartNo");
            if (pn != null && !pn.toString().isBlank()) distinctPartNoSet.add(pn.toString());
        }
        if (distinctPartNoSet.isEmpty()) return result;
        List<String> distinctPartNos = new ArrayList<>(distinctPartNoSet);

        for (DriverComp comp : comps) {
            try {
                // task-0721 B3：树页签(tab_type='BOM')不进合桶——其行由 BomTreeRenderService 整单渲染
                // (spine + 边键匹配)提供，不是简单的按 partNo 展开，合桶逻辑对它无意义且会被丢弃浪费。
                if (BomTreeRenderService.isQuoteTreeTabType(comp.tabType)) {
                    continue;
                }
                if (!componentDriverService.eligibleForQuoteBucket(comp.id)) {
                    // 不 eligible（含 lineItemId/spineKeys/composite/EXCEL）→ 不进 buckets，调用方逐行回落
                    continue;
                }
                // 一次 expandMulti(全部不同 partNo)，expandMulti 已按 hf_part_no 回分，
                // 每个 partNo 都有 entry（0 行也返空 resp），保证调用方 bucket.get(partNo) 不为 null。
                Map<String, ExpandDriverResponse> byPart =
                        componentDriverService.expandMulti(comp.id, customerId, distinctPartNos, null, null, null);
                result.put(comp.id, byPart);
                LOG.debugf("[quote-bucket] comp=%s eligible eligible parts=%d bucketEntries=%d",
                        comp.id, distinctPartNos.size(), byPart.size());
            } catch (Exception e) {
                LOG.warnf("[quote-bucket] comp=%s precompute 失败(跳过,回落逐行): %s", comp.id, e.getMessage());
                // 失败则不放入 result → 调用方对该组件逐行回落（保守安全）
            }
        }
        LOG.infof("[quote-bucket] quotation=%s eligibleComps=%d totalComps=%d distinctParts=%d",
                quotationId, result.size(), comps.size(), distinctPartNos.size());
        return result;
    }

    /** 防御性空响应（expandMulti 应已预置，理论上不触发）。 */
    private static ExpandDriverResponse emptyExpandResponse() {
        ExpandDriverResponse r = new ExpandDriverResponse();
        r.rows = new ArrayList<>();
        r.rowCount = 0;
        return r;
    }

    /**
     * repair-0729 Task2（空覆盖护栏）：判断一份 snapshot_rows JSON 是否为空
     * （{@code null}、空白字符串、JSON {@code null}、或空数组 {@code []}）。
     * 解析失败（脏 JSON）保守按"非空"处理，不拦截该次写入——护栏只拦"确认为空"的结果，
     * 不改变既有的异常/降级行为。
     */
    static boolean isEmptyRowsJson(String json) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode node = MAPPER.readTree(json);
            return node == null || node.isNull() || (node.isArray() && node.isEmpty());
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // task-0721 B3/B5 — 树页签物化辅助（提取料号 / 父子边收集 / __nodeType 注入 / 失败哨兵）
    // =========================================================================

    /**
     * task-0721（2026-07-21 补录）：把 fields JSON 字符串安全解析为 {@link JsonNode}
     * （供 {@link com.cpq.quotation.service.FormulaCalculator#computeRowKey} 按 partNoField 解析用）。
     * 解析失败 → 空数组（等价"该组件无字段定义"，computeRowKey 内部会因取不到值而返回 null，不抛异常）。
     */
    private static JsonNode parseFieldsJsonSafe(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(fieldsJson);
            return n != null && n.isArray() ? n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    // ───────────── task-0722：组件级 sort_field 行排序 ─────────────

    /**
     * 按组件 {@code sort_field} 对 driver 行做数字感知升序排序，返回<b>新副本</b>。
     * <ul>
     *   <li>树页签(tab_type=BOM)按树序渲染，不排；</li>
     *   <li>{@code sort_field} 未配 / 行数 &lt; 2 / 解析不到对应列 → 原样返回；</li>
     *   <li><b>绝不原地改</b> {@code rows}（可能是 expand 缓存的共享引用，AP-37 可变共享面）。</li>
     * </ul>
     */
    static List<ExpandDriverResponse.Row> sortRowsBySortField(List<ExpandDriverResponse.Row> rows, DriverComp comp) {
        if (rows == null || rows.size() < 2 || comp == null
                || comp.sortField == null || comp.sortField.isBlank()) return rows;
        if (BomTreeRenderService.isQuoteTreeTabType(comp.tabType)) return rows; // 树序为准
        String col = resolveDriverColumn(comp.sortField, comp.fields);
        if (col == null) return rows;
        List<ExpandDriverResponse.Row> sorted = new ArrayList<>(rows);
        final String key = col;
        sorted.sort(java.util.Comparator.comparing(
                r -> (r != null && r.driverRow != null) ? r.driverRow.get(key) : null,
                ConfigureSnapshotService::compareNumericAware));
        return sorted;
    }

    /** 字段名 → driver 行的列 key：查 fields JSON 里 name 匹配项的 default_source.path（或 basic_data_path）末段。 */
    static String resolveDriverColumn(String fieldName, String fieldsJson) {
        if (fieldName == null || fieldName.isBlank()) return null;
        try {
            JsonNode arr = MAPPER.readTree(fieldsJson == null ? "[]" : fieldsJson);
            if (arr == null || !arr.isArray()) return null;
            for (JsonNode f : arr) {
                if (!fieldName.equals(f.path("name").asText(null))) continue;
                String path = null;
                JsonNode ds = f.get("default_source");
                if (ds != null && ds.hasNonNull("path")) path = ds.get("path").asText();
                if ((path == null || path.isBlank()) && f.hasNonNull("basic_data_path")) {
                    path = f.get("basic_data_path").asText();
                }
                if (path == null || path.isBlank()) return null;
                int dot = path.lastIndexOf('.');
                return dot >= 0 ? path.substring(dot + 1) : path;
            }
        } catch (Exception ignore) { /* 解析失败 → 不排 */ }
        return null;
    }

    /** 数字感知比较：两值都可解析为数字 → 按数字；否则按字符串；null 殿后。 */
    static int compareNumericAware(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;   // null 排最后
        if (b == null) return -1;
        BigDecimal da = tryNum(a), db = tryNum(b);
        if (da != null && db != null) return da.compareTo(db);
        return a.toString().compareTo(b.toString());
    }

    private static BigDecimal tryNum(Object o) {
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /**
     * 从一个树页签组件的 precomputed baseRows（{@link BomTreeRenderService#treeRowNode} 产出）收集
     * 父子边（{@code __parentNo} → {@code __hfPartNo}）到类型判定上下文，供规则三（直接子节点结构推导）。
     * 多个树组件的 spine 边应完全相同（同一 line item 只有一棵 spine），重复调用安全（Set 去重）。
     */
    private static void addChildEdgesFromTreeRows(ArrayNode rows, BomNodeTypeResolver.TabHitContext ctx) {
        if (rows == null || ctx == null) return;
        for (JsonNode row : rows) {
            String parentNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
            String hfPartNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
            if (parentNo != null && !parentNo.isBlank() && hfPartNo != null && !hfPartNo.isBlank()) {
                ctx.addChild(parentNo, hfPartNo);
            }
        }
    }

    /**
     * 逐行判定 {@code __nodeType} 并写入（B5 宽松解析：无法确定 → 显式 null = "未判定"，不阻断物化，
     * api.md §0.2）。就地 mutate {@code rows} 中的 ObjectNode（本方法调用时机在序列化前，安全）。
     *
     * <p>树节点自身的料号身份取 {@code __hfPartNo}（{@link BomTreeRenderService#treeRowNode} 写入的
     * 权威系统列，来自递归 SQL 的 node_path，与该 Tab 具体挂了什么业务字段无关）——<b>不</b>按
     * {@code partNoField} 解析，因为 {@code partNoField} 是"非树页签的料号列配置"，树页签本身
     * 不要求配置它（api.md §1）。
     */
    private void injectNodeTypes(ArrayNode rows, BomNodeTypeResolver.TabHitContext ctx) {
        if (rows == null || ctx == null) return;
        for (JsonNode row : rows) {
            if (!(row instanceof ObjectNode obj)) continue;
            String materialNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
            BomNodeTypeResolver.Resolution resolution =
                    materialNo != null && !materialNo.isBlank() ? bomNodeTypeResolver.resolveLenient(materialNo, ctx) : null;
            if (resolution != null) {
                obj.put("__nodeType", resolution.nodeType);
            } else {
                obj.putNull("__nodeType");
            }
        }
    }

    /**
     * BL-0030 同款失败哨兵：{@link BomTreeRenderService#render} 整单渲染失败时，树页签落一个
     * 带原文错误信息的单行（业务列留空），供前端显式展示，而不是让整单快照 500 / 前端无限"加载中…"。
     */
    private ArrayNode buildTreeRenderErrorSentinel(String message) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode row = arr.addObject();
        row.set("driverRow", MAPPER.createObjectNode());
        row.set("basicDataValues", MAPPER.createObjectNode());
        row.put("__renderError", "报价树渲染失败: " + (message != null ? message : "未知错误"));
        row.putNull("__nodeType");
        return arr;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public UUID loadCustomerId(UUID quotationId) {
        List<Object> r = em.createNativeQuery("SELECT customer_id FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : UUID.fromString(r.get(0).toString());
    }

    /**
     * task-0725 T3-P1：供 {@link QuotePendingScope#open} 冻结判定用。查不到（quotationId 非法/已删除）
     * 时返回 null → open() 视作「无报价单上下文」存 null，等价不打开（安全兜底）。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public String loadQuotationStatus(UUID quotationId) {
        List<Object> r = em.createNativeQuery("SELECT status FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toString();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<DriverComp> loadDriverComponents(UUID quotationId) {
        // task-0806 B8：PUBLISHED/ARCHIVED（已有冻结快照）改走 PublishedTemplateReader，不再直读活
        // component 表；DRAFT（尚无快照，架构上允许——§5.1.2）继续走原 live JOIN 查询，行为不变。
        // 本方法服务的不只是"报价单渲染"这一已强制 PUBLISHED 的场景（task-0729），也被
        // snapshotQuotation/snapshotLines 用于草稿期"加产品整份快照"预写路径（实测 T7 用例
        // 直接构造 status=DRAFT 的模板验证该行为），故不能像 CardSnapshotService 的纯渲染读取点
        // 那样无条件假设"这里只服务已发布模板"。
        // driver_path 用于判定组合父级该"聚合子件"(composite_child_*_mirror)还是"父级展开"
        // ($zcj_bom 子配件清单)。task-0721 B3：附带 tab_type，供 BomTreeRenderService.isQuoteTreeTabType
        // 单一收口点路由判断。task-0721（2026-07-21 补录，2026-07-23 补 part_name_field）：附带
        // part_no_field/part_name_field/fields，供类型判定命中收集按"标识列"显式取值（料号列优先，
        // 名称列兜底；不再按字段名启发式猜测）。
        List<Object[]> qRows = em.createNativeQuery(
                "SELECT customer_template_id, (SELECT status FROM template WHERE id = q.customer_template_id) "
                        + "FROM quotation q WHERE q.id = :q")
                .setParameter("q", quotationId).getResultList();
        if (qRows.isEmpty() || qRows.get(0) == null || qRows.get(0)[0] == null) return List.of();
        UUID templateId = qRows.get(0)[0] instanceof UUID u ? u : UUID.fromString(qRows.get(0)[0].toString());
        String status = qRows.get(0)[1] != null ? qRows.get(0)[1].toString() : null;

        List<DriverComp> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();   // 与旧 SQL 的 DISTINCT c.id 同语义：同 componentId 只取一次
        if ("PUBLISHED".equals(status) || "ARCHIVED".equals(status)) {
            for (com.cpq.template.entity.TemplateComponentSnapshot s : publishedTemplateReader.driverCompsOf(templateId)) {
                if (!seen.add(s.componentId)) continue;
                DriverComp dc = new DriverComp();
                dc.id = s.componentId;
                dc.name = s.componentName;
                dc.driverPath = s.dataDriverPath;
                dc.tabType = s.tabType;
                dc.partNoField = s.partNoField;
                dc.fields = (s.fields != null && !s.fields.isBlank()) ? s.fields : "[]";
                dc.sortField = s.sortField;
                dc.partNameField = s.partNameField;
                out.add(dc);
            }
            return out;
        }

        // DRAFT（或状态查不到，理论不该发生但兜底同旧行为）：活表照旧
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT c.id, c.name, c.data_driver_path, c.tab_type, c.part_no_field, c.fields, "
                        + "c.sort_field, c.part_name_field "
                        + "FROM template_component tc "
                        + "JOIN component c ON c.id = tc.component_id "
                        + "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("tid", templateId).getResultList();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            DriverComp dc = new DriverComp();
            dc.id = UUID.fromString(r[0].toString());
            dc.name = r[1] != null ? r[1].toString() : null;
            dc.driverPath = r[2] != null ? r[2].toString() : null;
            dc.tabType = r[3] != null ? r[3].toString() : null;
            dc.partNoField = r[4] != null ? r[4].toString() : null;
            dc.fields = r[5] != null ? r[5].toString() : "[]";
            dc.sortField = r[6] != null ? r[6].toString() : null;
            dc.partNameField = r[7] != null ? r[7].toString() : null;
            out.add(dc);
        }
        return out;
    }

    /** task-0721 B3：本报价单的报价（customer）模板 id，供 {@link BomTreeRenderService#render} 用。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public UUID loadCustomerTemplateId(UUID quotationId) {
        List<Object> r = em.createNativeQuery("SELECT customer_template_id FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : UUID.fromString(r.get(0).toString());
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
                    "       AND mbi.characteristic = 'ASSEMBLY' AND mbi.component_no IS NOT NULL AND mbi.is_current = true)")
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

    /** 取本报价单 customer_template 的 components_snapshot(物化 row_data 用);缺失/解析失败 → null。
     *  只读 → SUPPORTS(表意:无需独立写事务;configure 已提交,SELECT 读最新已提交态即可)。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    @SuppressWarnings("unchecked")
    public JsonNode loadComponentsSnapshot(UUID quotationId) {
        try {
            List<Object> r = em.createNativeQuery(
                    "SELECT t.components_snapshot FROM quotation q " +
                    "JOIN template t ON t.id = q.customer_template_id WHERE q.id = :q")
                    .setParameter("q", quotationId).getResultList();
            if (r.isEmpty() || r.get(0) == null) return null;
            JsonNode node = MAPPER.readTree(r.get(0).toString());
            return node.isArray() ? node : null;
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 取 components_snapshot 失败: %s", quotationId, e.getMessage());
            return null;
        }
    }

    /**
     * 写时算齐 row_data(单趟拓扑序):用真实公式引擎把各非 SUBTOTAL 组件的 FORMULA 叶子列算进
     * {@code row_data}(扁平),供 Excel 视图({@code ComponentDataEffectiveRows.columnSums})无需用户
     * 编辑即正确求和。
     *
     * <p><b>对齐生产兄弟 {@link com.cpq.quotation.service.CardSnapshotService#assembleTabsWithFormulaResults}</b>:
     * 按组件级 {@link CrossTabComponentOrder#topoOrder} 排序(依赖边 = {@code cross_tab_ref} 源
     * + {@code component_subtotal} 跨组件引用),保证<b>被引用组件先于引用方计算</b>。每算完一个组件即:
     * <ol>
     *   <li>把其扁平行存入 {@code crossTabRows}(双键 componentId / componentCode)供后续 cross_tab_ref 引用;</li>
     *   <li>累计其按列小计到 {@code componentSubtotals}(键 {@code code#col} / {@code name#col},与
     *       {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} 一致)供后续 component_subtotal 求值;</li>
     *   <li>UPSERT 落库一次。</li>
     * </ol>
     * 因此到引用方计算时,它依赖的全部小计 / cross-tab 行均已就绪 —— 消除了旧 2-pass 里
     * {@code cross_tab_ref → 依赖 component_subtotal 的列} 链路读到引用方 Pass1 陈旧值的缺陷,
     * 同时去掉了冗余复算。
     *
     * <p>AP-51:行数权威 = snapshot_rows(materializeComponentRows 内按其行数迭代,绝不 Math.max);
     * SUBTOTAL 组件跳过(读时按公式重算)。
     */
    private void materializeRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                    Map<UUID, String> snapByComp) {
        materializeRowData(lineItemId, componentsSnapshot, snapByComp, false);
    }

    private void materializeRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                    Map<UUID, String> snapByComp, boolean batchWriteEnabled) {
        if (componentsSnapshot == null || snapByComp == null || snapByComp.isEmpty()) return;
        // 配置态：baseRows = snapshot_rows；editRows / rowKeyFields / 墓碑全空（行为与改造前 1:1）。
        Map<UUID, JsonNode> baseRowsByComp = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : snapByComp.entrySet()) {
            baseRowsByComp.put(e.getKey(), parseRows(e.getValue()));
        }
        materializeLineRowData(lineItemId, componentsSnapshot, baseRowsByComp,
                /* editRowsByComp */ Map.of(), /* rowKeyFieldsByComp */ Map.of(),
                /* deletedByComp */ Map.of(), batchWriteEnabled);
    }

    /**
     * spec 2026-08-03「键存在即权威」：把库内既有 {@code row_data} 里<b>已存在的 INPUT_* 键</b>
     * 原样盖回重物化结果（含显式清空 {@code ""}）。
     *
     * <p><b>为什么需要</b>：{@link #computeRowDataFromSnap} 是「纯按 snapshot 重物化」（editRows 恒空），
     * 会把用户手填/清空的 INPUT 值一并冲掉。只盖 INPUT_* 列，BASIC_DATA / DATA_SOURCE / FORMULA
     * 一律保留新算值 —— 那正是「刷新基础数据」该刷的部分。
     *
     * <p><b>行对齐用 {@code row_index}</b>（物化输出必带），不用数组下标：行增删时下标会错位，
     * {@code row_index} 匹配不上就自然退化为「用新值」，安全（AP-54 同族纪律：过滤后子集的下标
     * 绝不当原集合下标使）。
     *
     * <p>静态纯函数，无 IO、无状态，便于单测（{@code OverlayExistingInputKeysTest}）。
     *
     * @param componentsSnapshot 模板 components_snapshot（提供各组件的 fields → INPUT 字段清单）
     * @param fresh              重物化结果，<b>原地修改</b>
     * @param existingByComp     库内既有 row_data（componentId → 数组），可为 null/空 → 整体不动
     */
    static void overlayExistingInputKeys(JsonNode componentsSnapshot,
                                         Map<UUID, ArrayNode> fresh,
                                         Map<UUID, JsonNode> existingByComp) {
        if (componentsSnapshot == null || !componentsSnapshot.isArray()
                || fresh == null || fresh.isEmpty()
                || existingByComp == null || existingByComp.isEmpty()) {
            return;
        }
        for (JsonNode tab : componentsSnapshot) {
            UUID cid;
            try {
                cid = UUID.fromString(tab.path("componentId").asText(""));
            } catch (Exception ignore) {
                continue;
            }
            ArrayNode freshRows = fresh.get(cid);
            JsonNode existingRows = existingByComp.get(cid);
            if (freshRows == null || existingRows == null || !existingRows.isArray()) continue;

            // 本组件的 INPUT_* 字段名集合
            List<String> inputFields = new ArrayList<>();
            for (JsonNode f : tab.path("fields")) {
                String ft = f.path("field_type").asText("");
                if ("INPUT_NUMBER".equals(ft) || "INPUT_TEXT".equals(ft) || "INPUT".equals(ft)) {
                    String n = f.path("name").asText("");
                    if (!n.isEmpty()) inputFields.add(n);
                }
            }
            if (inputFields.isEmpty()) continue;

            // 既有行按 row_index 建索引
            Map<Integer, JsonNode> oldByRowIndex = new HashMap<>();
            for (JsonNode oldRow : existingRows) {
                if (!oldRow.isObject()) continue;
                JsonNode ri = oldRow.get("row_index");
                if (ri != null && ri.isInt()) oldByRowIndex.put(ri.asInt(), oldRow);
            }
            if (oldByRowIndex.isEmpty()) continue;

            for (JsonNode freshRow : freshRows) {
                if (!(freshRow instanceof ObjectNode)) continue;
                JsonNode ri = freshRow.get("row_index");
                if (ri == null || !ri.isInt()) continue;
                JsonNode oldRow = oldByRowIndex.get(ri.asInt());
                if (oldRow == null) continue;
                for (String fld : inputFields) {
                    // 「键存在即权威」：既有行有这个键就盖回（含 ""）；没有就用新烘的值。
                    if (oldRow.has(fld)) ((ObjectNode) freshRow).set(fld, oldRow.get(fld));
                }
            }
        }
    }

    /** 读本行各组件既有 row_data（componentId → 数组），供 {@link #overlayExistingInputKeys} 用。 */
    private Map<UUID, JsonNode> loadRowDataByComp(UUID lineItemId) {
        Map<UUID, JsonNode> out = new LinkedHashMap<>();
        if (lineItemId == null) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id, row_data FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND row_data IS NOT NULL")
            .setParameter("lid", lineItemId)
            .getResultList();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            try {
                JsonNode arr = MAPPER.readTree(r[1].toString());
                if (arr.isArray()) out.put((UUID) r[0], arr);
            } catch (Exception ignore) { /* 单组件解析失败不影响其它组件 */ }
        }
        return out;
    }

    /**
     * #2 物化批量:纯计算本行 row_data(componentId→ArrayNode),<b>不落库</b>,供整单收集后
     * 一次 {@link #writeRowDataBatchAllLines}。计算与 {@link #materializeRowData} 同款
     * (parse snapByComp → baseRows → {@link #computeLineRowData}),仅去掉 per-line 写。
     */
    private Map<UUID, ArrayNode> computeRowDataFromSnap(UUID lineItemId, JsonNode componentsSnapshot,
                                                        Map<UUID, String> snapByComp) {
        if (componentsSnapshot == null || snapByComp == null || snapByComp.isEmpty()) return Map.of();
        Map<UUID, JsonNode> baseRowsByComp = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : snapByComp.entrySet()) {
            baseRowsByComp.put(e.getKey(), parseRows(e.getValue()));
        }
        LinkedHashMap<UUID, ArrayNode> freshRowData = computeLineRowData(lineItemId, componentsSnapshot,
                baseRowsByComp, Map.of(), Map.of(), Map.of());
        // spec 2026-08-03：上面是「纯按 snapshot 重物化」(editRows 恒空)，会冲掉用户手填/清空的
        // INPUT 值。落库前把库内既有 row_data 的 INPUT 键盖回（含显式清空 ""），
        // BASIC_DATA/DATA_SOURCE/FORMULA 仍用新算值。降级：查库失败只记 warn，不中止整份快照。
        try {
            overlayExistingInputKeys(componentsSnapshot, freshRowData, loadRowDataByComp(lineItemId));
        } catch (Exception e) {
            LOG.warnf("[materialize-line] line=%s 既有 INPUT 键盖回失败(已降级，本次按重物化值落库): %s",
                    lineItemId, e.getMessage());
        }
        return freshRowData;
    }

    /**
     * #2 物化批量:<b>整单一次</b>写全部行的 row_data(替代每行 {@link #writeRowDataBatch} 的
     * N×REQUIRES_NEW)。两段式 {@code UPDATE…FROM(VALUES (lid,cid,rd))}(双键匹配)+ 未命中 INSERT,
     * 分块 200 元组。只更 row_data,不碰 snapshot_rows。与逐行 writeRowDataBatch 落库内容逐位一致。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeRowDataBatchAllLines(Map<UUID, Map<UUID, ArrayNode>> byLineComp) throws Exception {
        if (byLineComp == null || byLineComp.isEmpty()) return;
        final int CHUNK = 200;
        List<Object[]> tuples = new ArrayList<>();   // [lineItemId, componentId, rdJson]
        for (Map.Entry<UUID, Map<UUID, ArrayNode>> le : byLineComp.entrySet()) {
            if (le.getKey() == null || le.getValue() == null) continue;
            for (Map.Entry<UUID, ArrayNode> ce : le.getValue().entrySet()) {
                String rd = ce.getValue() != null ? MAPPER.writeValueAsString(ce.getValue()) : null;
                tuples.add(new Object[]{ le.getKey(), ce.getKey(), rd });
            }
        }
        if (tuples.isEmpty()) return;

        for (int start = 0; start < tuples.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, tuples.size());
            List<Object[]> chunk = tuples.subList(start, end);
            // ── ① 批量 UPDATE(双键 lid+cid,只更 row_data)──
            StringBuilder valB = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) valB.append(", ");
                String rdCast = chunk.get(i)[2] == null ? "NULL::jsonb" : "CAST(:rd" + i + " AS jsonb)";
                valB.append("(CAST(:lid").append(i).append(" AS uuid), CAST(:cid").append(i)
                    .append(" AS uuid), ").append(rdCast).append(")");
            }
            String updateSql =
                "UPDATE quotation_line_component_data d SET row_data = v.rd " +
                "FROM (VALUES " + valB + ") AS v(line_item_id, component_id, rd) " +
                "WHERE d.line_item_id = v.line_item_id AND d.component_id = v.component_id " +
                "RETURNING d.line_item_id, d.component_id";
            var upd = em.createNativeQuery(updateSql);
            for (int i = 0; i < chunk.size(); i++) {
                upd.setParameter("lid" + i, ((UUID) chunk.get(i)[0]).toString());
                upd.setParameter("cid" + i, ((UUID) chunk.get(i)[1]).toString());
                if (chunk.get(i)[2] != null) upd.setParameter("rd" + i, chunk.get(i)[2]);
            }
            @SuppressWarnings("unchecked")
            List<Object[]> returned = upd.getResultList();
            Set<String> updated = new HashSet<>();
            for (Object[] r : returned) updated.add(r[0].toString() + "|" + r[1].toString());

            // ── ② 未命中多值 INSERT ──
            List<Object[]> toInsert = new ArrayList<>();
            for (Object[] t : chunk) {
                if (!updated.contains(t[0].toString() + "|" + t[1].toString())) toInsert.add(t);
            }
            if (toInsert.isEmpty()) continue;
            StringBuilder insB = new StringBuilder();
            for (int i = 0; i < toInsert.size(); i++) {
                if (i > 0) insB.append(", ");
                String rdCast = toInsert.get(i)[2] == null ? "NULL::jsonb" : "CAST(:ird" + i + " AS jsonb)";
                insB.append("(:ilid").append(i).append(", :icid").append(i).append(", ").append(rdCast).append(", NOW())");
            }
            String insertSql =
                "INSERT INTO quotation_line_component_data (line_item_id, component_id, row_data, created_at) VALUES " + insB;
            var ins = em.createNativeQuery(insertSql);
            for (int i = 0; i < toInsert.size(); i++) {
                ins.setParameter("ilid" + i, (UUID) toInsert.get(i)[0]);
                ins.setParameter("icid" + i, (UUID) toInsert.get(i)[1]);
                if (toInsert.get(i)[2] != null) ins.setParameter("ird" + i, toInsert.get(i)[2]);
            }
            ins.executeUpdate();
        }
    }

    /**
     * 整行 row_data 物化（共享）——按组件拓扑序逐组件物化并逐组件 {@code writeRowData} 落库。
     *
     * <p>配置态（{@link #materializeRowData}）与报价失焦同步（
     * {@code CardSnapshotService.editCardValue}）共用此入口：前者传 editRows/rowKeyFields/墓碑 全空
     * （行为与改造前一致）；后者传本次编辑产生的真实 editRows / 各组件行键 / 永久删除墓碑，
     * 从而让<b>跨页签依赖</b>（如「来料.材料成本」引用「元素」列小计）随编辑一并重物化 →
     * Excel 视图（只读 row_data 的列求和，不在读时重算 FORMULA 叶子）随卡片同步更新。
     *
     * <p><b>拓扑序 + 跨组件累积（不可破坏）</b>：依赖在前、引用在后单趟物化；每物化完一个组件即把它的
     * 扁平行（{@code crossTabRows} 双键 componentId/componentCode）+ 列小计（{@code componentSubtotals}
     * 键 code#col / name#col）累积进上下文，故后物化的引用方能读到其依赖的<b>最新</b>值（含本次编辑）。
     *
     * <p>全程降级：单组件物化/写库失败仅记 warn，不中止整行；拓扑环 → 降级原序。AP-51：行数权威=baseRows。
     *
     * @param lineItemId         报价行
     * @param componentsSnapshot 模板 components_snapshot（各 tab 含 componentCode/fields/formulas）
     * @param baseRowsByComp     componentId → baseRows（= snapshot_rows，{@code [{driverRow,basicDataValues}]}）
     * @param editRowsByComp     componentId → editRows（含本次编辑；配置态传空 Map）
     * @param rowKeyFieldsByComp componentId → rowKeyFields 节点（对齐 editRows，AP-54；无则缺省）
     * @param deletedByComp      componentId → 永久删除墓碑列表（无则缺省）
     */
    public void materializeLineRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                       Map<UUID, JsonNode> baseRowsByComp,
                                       Map<UUID, JsonNode> editRowsByComp,
                                       Map<UUID, JsonNode> rowKeyFieldsByComp,
                                       Map<UUID, List<DeletedRowKeys.Tombstone>> deletedByComp) {
        materializeLineRowData(lineItemId, componentsSnapshot, baseRowsByComp, editRowsByComp,
                rowKeyFieldsByComp, deletedByComp, false);
    }

    /**
     * 整行 row_data 物化（共享）—— batchWriteEnabled=true 时一次 {@link #writeRowDataBatch}；
     * false 时逐组件 {@link #writeRowData}（原行为）。
     */
    public void materializeLineRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                       Map<UUID, JsonNode> baseRowsByComp,
                                       Map<UUID, JsonNode> editRowsByComp,
                                       Map<UUID, JsonNode> rowKeyFieldsByComp,
                                       Map<UUID, List<DeletedRowKeys.Tombstone>> deletedByComp,
                                       boolean batchWriteEnabled) {
        if (componentsSnapshot == null || baseRowsByComp == null || baseRowsByComp.isEmpty()) return;
        LinkedHashMap<UUID, ArrayNode> byComp = computeLineRowData(lineItemId, componentsSnapshot,
                baseRowsByComp, editRowsByComp, rowKeyFieldsByComp, deletedByComp);
        if (batchWriteEnabled) {
            // Phase 1: 整行一次 REQUIRES_NEW（N×M → N×1）
            try {
                self.writeRowDataBatch(lineItemId, byComp);
            } catch (Exception ex) {
                LOG.warnf("[materialize-line] line=%s 批量写 row_data 失败(已降级逐行): %s",
                        lineItemId, ex.getMessage());
                // 降级逐行写（防止整行丢失）
                for (Map.Entry<UUID, ArrayNode> e : byComp.entrySet()) {
                    try {
                        self.writeRowData(lineItemId, e.getKey(), MAPPER.writeValueAsString(e.getValue()));
                    } catch (Exception ex2) {
                        LOG.warnf("[materialize-line] line=%s comp=%s 降级写 row_data 也失败: %s",
                                lineItemId, e.getKey(), ex2.getMessage());
                    }
                }
            }
        } else {
            // kill switch OFF：原逐行写
            for (Map.Entry<UUID, ArrayNode> e : byComp.entrySet()) {
                try {
                    self.writeRowData(lineItemId, e.getKey(), MAPPER.writeValueAsString(e.getValue()));
                } catch (Exception ex) {
                    LOG.warnf("[materialize-line] line=%s comp=%s 写 row_data 失败: %s",
                            lineItemId, e.getKey(), ex.getMessage());
                }
            }
        }
    }

    /**
     * 纯计算：按组件拓扑序物化整行各非 SUBTOTAL 组件的扁平 row_data，<b>不落库</b>（便于单测）。
     * 拓扑序 + 跨组件 {@code crossTabRows}/{@code componentSubtotals} 累积逻辑与原
     * {@link #materializeRowData} 完全一致；额外按 componentId 透传 editRows/rowKeyFields/墓碑。
     *
     * @return componentId → 扁平 row_data（按拓扑序的 LinkedHashMap，仅含成功物化的非 SUBTOTAL 组件）
     */
    public LinkedHashMap<UUID, ArrayNode> computeLineRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                                             Map<UUID, JsonNode> baseRowsByComp,
                                                             Map<UUID, JsonNode> editRowsByComp,
                                                             Map<UUID, JsonNode> rowKeyFieldsByComp,
                                                             Map<UUID, List<DeletedRowKeys.Tombstone>> deletedByComp) {
        LinkedHashMap<UUID, ArrayNode> result = new LinkedHashMap<>();
        if (componentsSnapshot == null || baseRowsByComp == null || baseRowsByComp.isEmpty()) return result;

        Map<UUID, JsonNode> editsByComp = editRowsByComp != null ? editRowsByComp : Map.of();
        Map<UUID, JsonNode> rkfByComp = rowKeyFieldsByComp != null ? rowKeyFieldsByComp : Map.of();
        Map<UUID, List<DeletedRowKeys.Tombstone>> delByComp = deletedByComp != null ? deletedByComp : Map.of();

        // componentId → snapshot tab(仅非 SUBTOTAL,且本行有 baseRows)
        Map<UUID, JsonNode> tabByComp = new LinkedHashMap<>();
        for (JsonNode tab : componentsSnapshot) {
            String type = tab.path("componentType").asText("NORMAL");
            if ("SUBTOTAL".equals(type)) continue; // 读时重算,不物化
            UUID cid = asUuid(tab.path("componentId").asText(null));
            if (cid != null && baseRowsByComp.containsKey(cid)) tabByComp.put(cid, tab);
        }
        if (tabByComp.isEmpty()) return result;

        // ── 组件级拓扑序(与生产兄弟 assembleTabsWithFormulaResults 同款) ──
        // 建图交给 CrossTabComponentOrder.buildComponentDeps:cross_tab_ref 全量建边;
        // component_subtotal 按列粒度判定(repair-0803)——引用零依赖列(INPUT_NUMBER 等)不建边,
        // 避免把列级直线依赖链折成页签级假环(参见该方法 javadoc)。
        List<String> compIds = new ArrayList<>();
        List<CrossTabComponentOrder.TabDep> tabDeps = new ArrayList<>();
        for (Map.Entry<UUID, JsonNode> e : tabByComp.entrySet()) {
            String cid = e.getKey().toString();
            JsonNode tab = e.getValue();
            compIds.add(cid);
            tabDeps.add(new CrossTabComponentOrder.TabDep(cid,
                    tab.path("componentCode").asText(""), tab.path("tabName").asText(""),
                    tab.path("formulas"), tab.path("fields")));
        }
        List<String> order;
        try {
            // repair-0803 FR-12：环文案用页签名称（降级日志因此可读，不再是一串 componentId）
            Map<String, String> tabNameById = new LinkedHashMap<>();
            for (CrossTabComponentOrder.TabDep td : tabDeps) {
                String nm = (td.tabName() != null && !td.tabName().isBlank()) ? td.tabName() : td.code();
                if (nm != null && !nm.isBlank()) tabNameById.put(td.cid(), nm);
            }
            order = CrossTabComponentOrder.topoOrder(
                    compIds, CrossTabComponentOrder.buildComponentDeps(tabDeps), tabNameById);
        } catch (Exception cyc) {
            // 环(配置异常)→ 降级按原序物化,绝不中止整份快照(沿用本类全程降级纪律)。
            LOG.warnf("[materialize-line] line=%s 组件拓扑序失败(降级原序): %s", lineItemId, cyc.getMessage());
            order = compIds;
        }

        Map<String, BigDecimal> componentSubtotals = new HashMap<>();
        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();

        // ── 单趟拓扑序:依赖在前,引用在后 —— 引用方计算时其依赖小计 / cross-tab 行已就绪 ──
        for (String cidStr : order) {
            UUID cid = asUuid(cidStr);
            JsonNode tab = cid != null ? tabByComp.get(cid) : null;
            if (tab == null) continue;
            String code = tab.path("componentCode").asText(null);
            String tabName = tab.path("tabName").asText(null);
            JsonNode snapshotRows = baseRowsByComp.get(cid);

            // 本组件 editRows / rowKeyFields / 墓碑（缺省 → null/空，物化退化为配置态口径）。
            JsonNode editRows = editsByComp.get(cid);
            JsonNode rowKeyFields = rkfByComp.get(cid);
            List<DeletedRowKeys.Tombstone> tombstones = delByComp.get(cid);
            List<String> rowKeyFieldNames = rowKeyFieldNamesOf(rowKeyFields);

            ArrayNode flat;
            try {
                flat = rowDataMaterializer.materializeComponentRows(
                        componentsSnapshot, code, snapshotRows,
                        editRows, rowKeyFields,
                        componentSubtotals, crossTabRows,
                        tombstones, rowKeyFieldNames);
            } catch (Exception ex) {
                LOG.warnf("[materialize-line] line=%s comp=%s 物化失败(降级跳过): %s",
                        lineItemId, cid, ex.getMessage());
                continue;
            }

            // 单位换算(cross_tab 物化点,与生产兄弟 CardSnapshotService.convertRowsForCrossTab 对齐):
            // 跨页签引用方 + 列小计求和都读 canonical(按同行 unit_source_field 列归一到 KG/PCS)。
            // 落库 flat 保持原值(与卡片 resolvedRows 落库不换算同纪律);仅换喂下游的副本。
            // 缺此步时 crossTabRows/componentSubtotals 喂原始值 → Excel(读 row_data) 与卡片(读 formulaResults,
            // 卡片链已换算)在含单位列的跨页签引用处分叉(如 来料.材料成本 引 元素.单价 g/PCS)。
            JsonNode fields = tab.path("fields");
            List<Map<String, Object>> canonRows = convertRowsForCrossTab(fields, toRowMaps(flat));

            // crossTabRows(双键 componentId / componentCode):供后续组件 cross_tab_ref 引用(canonical)。
            crossTabRows.put(cidStr, canonRows);
            if (code != null && !code.isBlank()) crossTabRows.put(code, canonRows);

            // 列小计累计(键 code#col / name#col):供后续组件 component_subtotal token 求值(canonical)。
            // BL-0017:同时登记金额列哨兵键 code#__amount_total__ / tabName#__amount_total__(供 [页签(总计)])。
            accumulateColumnSubtotals(canonRows, code, tabName, componentSubtotals,
                com.cpq.quotation.service.card.ComponentDataEffectiveRows.amountColsFromFields(fields));

            result.put(cid, flat);
        }
        return result;
    }

    /** 从 rowKeyFields 节点（{@code ["料号",…]}）提取字段名列表，供墓碑指纹/effKey 对齐（与 CardSnapshotService 同口径）。 */
    private static List<String> rowKeyFieldNamesOf(JsonNode rowKeyFieldsNode) {
        if (rowKeyFieldsNode == null || !rowKeyFieldsNode.isArray()) return List.of();
        List<String> names = new ArrayList<>(rowKeyFieldsNode.size());
        for (JsonNode n : rowKeyFieldsNode) {
            String name = n.asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /** 解析 snapshot_rows JSON 为 JsonNode 数组;失败 → 空数组。 */
    private JsonNode parseRows(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    /** 扁平行 ArrayNode → List<Map>(供 crossTabRows)。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRowMaps(ArrayNode flat) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (flat == null) return out;
        for (JsonNode r : flat) {
            try {
                out.add(MAPPER.convertValue(r, Map.class));
            } catch (Exception ignore) { /* 跳过坏行 */ }
        }
        return out;
    }

    /**
     * 单位换算(cross_tab 物化点):把一组扁平行换算成 canonical 副本喂 crossTabRows/列小计,原行不变。
     * 配 {@code unit_source_field} 的列按同行单位文本归一到 KG/PCS;未配列原样。
     * 与生产兄弟 {@code CardSnapshotService.convertRowsForCrossTab} 同口径(同调 {@code UnitConversion.convertObjectRow})。
     */
    private List<Map<String, Object>> convertRowsForCrossTab(JsonNode fields, List<Map<String, Object>> rows) {
        if (rows == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }
        return out;
    }

    /**
     * 对(已换算 canonical)扁平行各数值列求和,写入 componentSubtotals(键 code#col / name#col),与 ComponentDataEffectiveRows 一致。
     * 入参为换算后行(见 {@link #convertRowsForCrossTab}),与卡片 {@code backfillSubtotalsFromResolved} 求和用 canonical 一致。
     */
    private void accumulateColumnSubtotals(List<Map<String, Object>> rows, String code, String tabName,
                                           Map<String, BigDecimal> componentSubtotals,
                                           java.util.Set<String> amountCols) {
        if (rows == null) return;
        // task-0801 B4-2（审计发现，与 CardSnapshotService#backfillSubtotalsFromResolved 同一
        // 根因的孪生方法）：累加过程和 componentSubtotals 均使用 BigDecimal，禁止浮点中转。
        Map<String, java.math.BigDecimal> colSums = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            for (Map.Entry<String, Object> en : row.entrySet()) {
                if (en.getValue() instanceof Number n) {
                    colSums.merge(en.getKey(), com.cpq.common.PrecisionPolicy.of(n), java.math.BigDecimal::add);
                }
            }
        }
        BigDecimal amountTotal = BigDecimal.ZERO;
        for (Map.Entry<String, java.math.BigDecimal> e : colSums.entrySet()) {
            BigDecimal v = e.getValue();
            if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + e.getKey(), v);
            if (tabName != null && !tabName.isBlank()) componentSubtotals.put(tabName + "#" + e.getKey(), v);
            if (amountCols != null && amountCols.contains(e.getKey())) amountTotal = amountTotal.add(v);
        }
        // BL-0017 哨兵键(加性,不动裸键):code#__amount_total__ / tabName#__amount_total__ = Σ金额列。
        String akey = com.cpq.quotation.service.card.ComponentDataEffectiveRows.AMOUNT_TOTAL_KEY;
        if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + akey, amountTotal);
        if (tabName != null && !tabName.isBlank()) componentSubtotals.put(tabName + "#" + akey, amountTotal);
    }

    /**
     * 写一个组件物化后的 row_data(REQUIRES_NEW 独立小事务)。
     * UPSERT:行已存在(writeSnapshot 已建)→ UPDATE row_data;不存在 → INSERT。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeRowData(UUID lineItemId, UUID componentId, String rowDataJson) {
        String expr = rowDataJson == null ? "NULL" : "CAST(:rd AS jsonb)";
        var update = em.createNativeQuery(
                "UPDATE quotation_line_component_data SET row_data = " + expr + " " +
                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", componentId);
        if (rowDataJson != null) update.setParameter("rd", rowDataJson);
        int updated = update.executeUpdate();
        if (updated == 0) {
            var insert = em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data " +
                    "(line_item_id, component_id, row_data, created_at) " +
                    "VALUES (:lid, :cid, " + expr + ", NOW())")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId);
            if (rowDataJson != null) insert.setParameter("rd", rowDataJson);
            insert.executeUpdate();
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 批量写 — kill switch: cpq.firstsave-batch-write (默认 true)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase 1 批量写快照（路线 A 两段式，整行一个 REQUIRES_NEW 事务）。
     *
     * <p>① 一条 {@code UPDATE … FROM (VALUES …) AS v RETURNING d.component_id}
     * 批量更新 snapshot_rows（保留 row_data，tab_name 用 COALESCE 不覆盖已有值）；
     * ② RETURNING 未覆盖的 component_id 一条多值 {@code INSERT}（首存全新行场景）。
     *
     * <p>⚠ 表无 {@code UNIQUE(line_item_id, component_id)}（V11 只有 PK id + idx_qlcd_line），
     * 不能用 ON CONFLICT；两段式与逐行 UPSERT 语义 1:1。
     *
     * <p>NULL 处理：rowsJson==null → VALUES 列用 {@code NULL::jsonb}（显式 cast，避免
     * PG "could not determine data type"，同 P2-Q05 陷阱）。
     *
     * @param lineItemId 报价行
     * @param rows       整行各组件快照（componentId, tabName, rowsJson）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeSnapshotBatch(UUID lineItemId, List<SnapRow> rows) {
        if (lineItemId == null || rows == null || rows.isEmpty()) return;

        // ── ① 批量 UPDATE ──
        // 构造 VALUES 子句：每个元素一行，NULL jsonb 显式 cast
        StringBuilder valBuilder = new StringBuilder();
        List<Object[]> params = new ArrayList<>();  // [componentId, rowsJson, tabName]
        for (int i = 0; i < rows.size(); i++) {
            SnapRow sr = rows.get(i);
            if (i > 0) valBuilder.append(", ");
            if (sr.rowsJson == null) {
                valBuilder.append("(CAST(:cid").append(i).append(" AS uuid), NULL::jsonb, CAST(:tab").append(i).append(" AS text))");
            } else {
                valBuilder.append("(CAST(:cid").append(i).append(" AS uuid), CAST(:rows").append(i).append(" AS jsonb), CAST(:tab").append(i).append(" AS text))");
            }
            params.add(new Object[]{sr.componentId, sr.rowsJson, sr.tabName});
        }

        String updateSql =
                "UPDATE quotation_line_component_data d " +
                "SET snapshot_rows = v.rows, snapshot_at = NOW(), tab_name = COALESCE(d.tab_name, v.tab) " +
                "FROM (VALUES " + valBuilder + ") AS v(component_id, rows, tab) " +
                "WHERE d.line_item_id = :lid AND d.component_id = v.component_id " +
                "RETURNING d.component_id";

        var updateQuery = em.createNativeQuery(updateSql);
        updateQuery.setParameter("lid", lineItemId);
        for (int i = 0; i < params.size(); i++) {
            Object[] p = params.get(i);
            updateQuery.setParameter("cid" + i, p[0].toString());  // UUID as string for CAST
            if (p[1] != null) updateQuery.setParameter("rows" + i, p[1]);
            updateQuery.setParameter("tab" + i, p[2]);
        }

        @SuppressWarnings("unchecked")
        List<Object> returned = updateQuery.getResultList();
        Set<String> updated = new HashSet<>();
        for (Object o : returned) updated.add(o.toString());

        // ── ② 未命中的多值 INSERT ──
        List<SnapRow> toInsert = new ArrayList<>();
        for (SnapRow sr : rows) {
            if (!updated.contains(sr.componentId.toString())) toInsert.add(sr);
        }
        if (toInsert.isEmpty()) return;

        StringBuilder insBuilder = new StringBuilder();
        for (int i = 0; i < toInsert.size(); i++) {
            SnapRow sr = toInsert.get(i);
            if (i > 0) insBuilder.append(", ");
            if (sr.rowsJson == null) {
                insBuilder.append("(:ilid").append(i).append(", :icid").append(i)
                          .append(", :itab").append(i).append(", NULL::jsonb, NOW())");
            } else {
                insBuilder.append("(:ilid").append(i).append(", :icid").append(i)
                          .append(", :itab").append(i).append(", CAST(:irows").append(i).append(" AS jsonb), NOW())");
            }
        }

        String insertSql =
                "INSERT INTO quotation_line_component_data " +
                "(line_item_id, component_id, tab_name, snapshot_rows, snapshot_at) " +
                "VALUES " + insBuilder;

        var insertQuery = em.createNativeQuery(insertSql);
        for (int i = 0; i < toInsert.size(); i++) {
            SnapRow sr = toInsert.get(i);
            insertQuery.setParameter("ilid" + i, lineItemId);
            insertQuery.setParameter("icid" + i, sr.componentId);
            insertQuery.setParameter("itab" + i, sr.tabName);
            if (sr.rowsJson != null) insertQuery.setParameter("irows" + i, sr.rowsJson);
        }
        insertQuery.executeUpdate();
    }

    /**
     * Phase 2 落库批量：<b>整单一次</b>写全部行的 snapshot_rows（替代 {@link #writeSnapshotBatch} 每行一个
     * REQUIRES_NEW，N×→1）。两段式 {@code UPDATE…FROM(VALUES (lid,cid,rows,tab)…)}（按
     * {@code (line_item_id, component_id)} 双键匹配）+ 未命中多值 INSERT，与逐行 UPSERT 语义逐位一致。
     * 分块（{@code CHUNK} 个 (lid,cid) 元组/批）避免参数上限。
     *
     * <p>等价：每个 (lid,cid) 元组唯一 → 跨行批量与逐行写最终内容相同（snapshot_rows/tab_name 同值；
     * created_at/snapshot_at 为 NOW() 元数据，本就非确定，不参与渲染/golden）。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeSnapshotBatchAllLines(Map<UUID, List<SnapRow>> byLine) {
        if (byLine == null || byLine.isEmpty()) return;
        final int CHUNK = 200;
        // 展平成 (lineItemId, SnapRow) 元组，确定序（按 lineItemId 插入序 + 组件序）
        List<Object[]> tuples = new ArrayList<>();   // [lineItemId, SnapRow]
        for (Map.Entry<UUID, List<SnapRow>> e : byLine.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (SnapRow sr : e.getValue()) tuples.add(new Object[]{ e.getKey(), sr });
        }
        if (tuples.isEmpty()) return;

        for (int start = 0; start < tuples.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, tuples.size());
            List<Object[]> chunk = tuples.subList(start, end);

            // ── ① 批量 UPDATE（双键 lid+cid）──
            StringBuilder valB = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                SnapRow sr = (SnapRow) chunk.get(i)[1];
                if (i > 0) valB.append(", ");
                String rowsCast = sr.rowsJson == null ? "NULL::jsonb" : "CAST(:rows" + i + " AS jsonb)";
                valB.append("(CAST(:lid").append(i).append(" AS uuid), CAST(:cid").append(i)
                    .append(" AS uuid), ").append(rowsCast).append(", CAST(:tab").append(i).append(" AS text))");
            }
            String updateSql =
                "UPDATE quotation_line_component_data d " +
                "SET snapshot_rows = v.rows, snapshot_at = NOW(), tab_name = COALESCE(d.tab_name, v.tab) " +
                "FROM (VALUES " + valB + ") AS v(line_item_id, component_id, rows, tab) " +
                "WHERE d.line_item_id = v.line_item_id AND d.component_id = v.component_id " +
                "RETURNING d.line_item_id, d.component_id";
            var upd = em.createNativeQuery(updateSql);
            for (int i = 0; i < chunk.size(); i++) {
                UUID lid = (UUID) chunk.get(i)[0];
                SnapRow sr = (SnapRow) chunk.get(i)[1];
                upd.setParameter("lid" + i, lid.toString());
                upd.setParameter("cid" + i, sr.componentId.toString());
                if (sr.rowsJson != null) upd.setParameter("rows" + i, sr.rowsJson);
                upd.setParameter("tab" + i, sr.tabName);
            }
            @SuppressWarnings("unchecked")
            List<Object[]> returned = upd.getResultList();
            Set<String> updated = new HashSet<>();
            for (Object[] r : returned) updated.add(r[0].toString() + "|" + r[1].toString());

            // ── ② 未命中多值 INSERT ──
            List<Object[]> toInsert = new ArrayList<>();
            for (Object[] t : chunk) {
                UUID lid = (UUID) t[0];
                SnapRow sr = (SnapRow) t[1];
                if (!updated.contains(lid.toString() + "|" + sr.componentId.toString())) toInsert.add(t);
            }
            if (toInsert.isEmpty()) continue;
            StringBuilder insB = new StringBuilder();
            for (int i = 0; i < toInsert.size(); i++) {
                SnapRow sr = (SnapRow) toInsert.get(i)[1];
                if (i > 0) insB.append(", ");
                String rowsCast = sr.rowsJson == null ? "NULL::jsonb" : "CAST(:irows" + i + " AS jsonb)";
                insB.append("(:ilid").append(i).append(", :icid").append(i)
                    .append(", :itab").append(i).append(", ").append(rowsCast).append(", NOW())");
            }
            String insertSql =
                "INSERT INTO quotation_line_component_data " +
                "(line_item_id, component_id, tab_name, snapshot_rows, snapshot_at) VALUES " + insB;
            var ins = em.createNativeQuery(insertSql);
            for (int i = 0; i < toInsert.size(); i++) {
                UUID lid = (UUID) toInsert.get(i)[0];
                SnapRow sr = (SnapRow) toInsert.get(i)[1];
                ins.setParameter("ilid" + i, lid);
                ins.setParameter("icid" + i, sr.componentId);
                ins.setParameter("itab" + i, sr.tabName);
                if (sr.rowsJson != null) ins.setParameter("irows" + i, sr.rowsJson);
            }
            ins.executeUpdate();
        }
    }

    /**
     * Phase 1 批量写 row_data（路线 A 两段式，整行一个 REQUIRES_NEW 事务）。
     *
     * <p>只更 row_data，保留 snapshot_rows（互不清零）。
     * 实务上 {@link #writeSnapshotBatch} 已先建行，UPDATE 几乎全命中，INSERT 段通常空。
     *
     * @param lineItemId 报价行
     * @param byComp     componentId → 扁平 row_data（ArrayNode）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeRowDataBatch(UUID lineItemId, Map<UUID, ArrayNode> byComp) throws Exception {
        if (lineItemId == null || byComp == null || byComp.isEmpty()) return;

        List<Map.Entry<UUID, ArrayNode>> entries = new ArrayList<>(byComp.entrySet());

        // ── ① 批量 UPDATE（只更 row_data，不碰 snapshot_rows）──
        StringBuilder valBuilder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<UUID, ArrayNode> e = entries.get(i);
            if (i > 0) valBuilder.append(", ");
            String rdJson = e.getValue() != null ? MAPPER.writeValueAsString(e.getValue()) : null;
            if (rdJson == null) {
                valBuilder.append("(CAST(:cid").append(i).append(" AS uuid), NULL::jsonb)");
            } else {
                valBuilder.append("(CAST(:cid").append(i).append(" AS uuid), CAST(:rd").append(i).append(" AS jsonb))");
            }
        }

        String updateSql =
                "UPDATE quotation_line_component_data d " +
                "SET row_data = v.rd " +
                "FROM (VALUES " + valBuilder + ") AS v(component_id, rd) " +
                "WHERE d.line_item_id = :lid AND d.component_id = v.component_id " +
                "RETURNING d.component_id";

        var updateQuery = em.createNativeQuery(updateSql);
        updateQuery.setParameter("lid", lineItemId);
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<UUID, ArrayNode> e = entries.get(i);
            updateQuery.setParameter("cid" + i, e.getKey().toString());
            if (e.getValue() != null) {
                updateQuery.setParameter("rd" + i, MAPPER.writeValueAsString(e.getValue()));
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> returned = updateQuery.getResultList();
        Set<String> updated = new HashSet<>();
        for (Object o : returned) updated.add(o.toString());

        // ── ② 未命中的多值 INSERT ──
        List<Map.Entry<UUID, ArrayNode>> toInsert = new ArrayList<>();
        for (Map.Entry<UUID, ArrayNode> e : entries) {
            if (!updated.contains(e.getKey().toString())) toInsert.add(e);
        }
        if (toInsert.isEmpty()) return;

        StringBuilder insBuilder = new StringBuilder();
        List<String[]> insertParams = new ArrayList<>();
        for (int i = 0; i < toInsert.size(); i++) {
            Map.Entry<UUID, ArrayNode> e = toInsert.get(i);
            if (i > 0) insBuilder.append(", ");
            String rdJson = e.getValue() != null ? MAPPER.writeValueAsString(e.getValue()) : null;
            if (rdJson == null) {
                insBuilder.append("(:ilid").append(i).append(", :icid").append(i).append(", NULL::jsonb, NOW())");
            } else {
                insBuilder.append("(:ilid").append(i).append(", :icid").append(i)
                          .append(", CAST(:ird").append(i).append(" AS jsonb), NOW())");
            }
            insertParams.add(new String[]{e.getKey().toString(), rdJson});
        }

        String insertSql =
                "INSERT INTO quotation_line_component_data " +
                "(line_item_id, component_id, row_data, created_at) " +
                "VALUES " + insBuilder;

        var insertQuery = em.createNativeQuery(insertSql);
        for (int i = 0; i < toInsert.size(); i++) {
            String[] p = insertParams.get(i);
            insertQuery.setParameter("ilid" + i, lineItemId);
            insertQuery.setParameter("icid" + i, UUID.fromString(p[0]));
            if (p[1] != null) insertQuery.setParameter("ird" + i, p[1]);
        }
        insertQuery.executeUpdate();
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
