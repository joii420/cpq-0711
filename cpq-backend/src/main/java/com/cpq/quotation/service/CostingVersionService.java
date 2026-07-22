package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import com.cpq.quotation.dto.VersionOptionsResponseDTO;
import com.cpq.quotation.dto.VersionSwitchRequest;
import com.cpq.quotation.dto.VersionSwitchResponseDTO;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.CostingOrderVersionOverride;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * task-0713 B6/B7：核价单版本下拉查询 + 切换版本核心写操作。
 *
 * <p>角色/状态门禁交由 {@code CostingOrderResource} 的类级
 * {@code @RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})} 承担（与既有
 * {@code getCostingOrderById} 同款信任边界），本服务只做状态机校验（PENDING）。
 */
@ApplicationScoped
public class CostingVersionService {

    private static final Logger LOG = Logger.getLogger(CostingVersionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    CardSnapshotService cardSnapshotService;

    @Inject
    BomTreeRenderService bomTreeRenderService;

    // =========================================================================
    // B6：版本下拉（列出模式）
    // =========================================================================

    /**
     * 查询某料号在某页签的可选版本（api.md §2）。列出模式（{@code :versionFilter}→TRUE）+
     * partNo 限定，独立轻查、不走带缓存的 {@code expand}（守 AP-37 串号）。
     *
     * <p><b>树组件特例</b>（发现于实现期，非既定设计）：主树/子配件类组件的 $view（如 pj_view/
     * zpj_view）是「边」形态（一行 = 一条 parent→child 边），其 {@code :total_material_no}
     * 收窄谓词过滤的是<b>边的子端</b>（{@code component_no}），而版本下拉问的是「这个 partNo
     * 自己的 BOM 有哪些版本」——这个问题的答案落在<b>边的父端</b>（{@code material_no}），与
     * {@code total_material_no} 的语义正交，通用的 {@code expandUncached} 整视图扫描找不到
     * 任何以 partNo 为父端的边（除非恰好也是别人的子件）。故树组件直接查 material_bom_item
     * 本表的 distinct bom_version（与 R1 递归 SQL 本就硬编码同一张表/同一组常量一致，未新增
     * 耦合）；非树（材质/工序/元素/组合工艺）组件走通用 $view 扫描路径。
     */
    public VersionOptionsResponseDTO listVersionOptions(UUID coid, UUID lineItemId, UUID componentId, String partNo) {
        CostingOrder co = CostingOrder.findById(coid);
        if (co == null) throw new BusinessException(404, "核价单不存在");
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) throw new BusinessException(404, "报价行不存在");
        Quotation q = Quotation.findById(li.quotationId);
        UUID customerId = q != null ? q.customerId : null;

        TreeSet<String> options = new TreeSet<>(CostingVersionService::compareVersionDesc);
        String isCurrentVersion = null; // is_current=true 对应的版本（override 缺失时的兜底 currentVersion）

        if (isTreeComponent(componentId)) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT bom_version, is_current FROM material_bom_item " +
                                    "WHERE system_type='PRICING' AND customer_no='_GLOBAL_' AND material_no=:p " +
                                    "AND bom_version IS NOT NULL")
                    .setParameter("p", partNo).getResultList();
            for (Object[] r : rows) {
                if (r[0] == null) continue;
                String v = r[0].toString();
                options.add(v);
                if (r[1] instanceof Boolean b && b) isCurrentVersion = v;
            }
        } else {
            List<ExpandDriverResponse.Row> listRows = expandRows(componentId, customerId, partNo, null, BomTreeVarsContext.Mode.LIST);
            for (ExpandDriverResponse.Row row : listRows) {
                String mn = partNoOf(row.driverRow);
                if (mn == null || !mn.equals(partNo)) continue;
                Object vv = row.driverRow.get("view_version");
                if (vv != null) options.add(vv.toString());
            }
            List<ExpandDriverResponse.Row> curRows = expandRows(componentId, customerId, partNo, Map.of(), BomTreeVarsContext.Mode.RENDER);
            for (ExpandDriverResponse.Row row : curRows) {
                String mn = partNoOf(row.driverRow);
                if (mn == null || !mn.equals(partNo)) continue;
                Object vv = row.driverRow.get("view_version");
                if (vv != null) { isCurrentVersion = vv.toString(); break; }
            }
        }

        String currentVersion;
        CostingOrderVersionOverride ov = CostingOrderVersionOverride.find(coid, componentId, partNo);
        currentVersion = (ov != null) ? ov.viewVersion : isCurrentVersion;

        VersionOptionsResponseDTO dto = new VersionOptionsResponseDTO();
        dto.componentId = componentId.toString();
        dto.partNo = partNo;
        dto.currentVersion = currentVersion;
        dto.options = new ArrayList<>(options);
        return dto;
    }

    /** 组件是否为主树/子配件类（{@code bom_recursive_expand=true}）。不存在的 componentId 抛 404。 */
    private boolean isTreeComponent(UUID componentId) {
        Object flagObj;
        try {
            flagObj = em.createNativeQuery("SELECT c.bom_recursive_expand FROM component c WHERE c.id = :cid")
                    .setParameter("cid", componentId).getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            throw new BusinessException(404, "组件不存在: " + componentId);
        }
        return (flagObj instanceof Boolean b) && b;
    }

    // =========================================================================
    // B7：切换版本（核心写操作）
    // =========================================================================

    /**
     * 切换版本（api.md §3）：单 {@code @Transactional} + {@code SELECT...FOR UPDATE} 锁
     * costing_order + upsert override 后 flush 再重算。重查 scope 最小化（主树=该 line 各
     * driver 组件跑一次 $view；非主树=仅该组件 $view 跑一次），重装 scope 恒整卡（未重查页签
     * 用缓存 baseRows），成本 rollup 落后端（{@code buildCostingCardValues}
     * {@code assembleTabsWithFormulaResults} 已有的公式引擎），不回写
     * {@code quotation_line_item.costing_card_values}。
     */
    @Transactional
    public VersionSwitchResponseDTO switchVersion(UUID coid, VersionSwitchRequest req) {
        if (req == null || req.lineItemId == null || req.componentId == null
                || req.partNo == null || req.partNo.isBlank() || req.viewVersion == null || req.viewVersion.isBlank()) {
            throw new BusinessException(400, "参数不完整：lineItemId/componentId/partNo/viewVersion 均为必填");
        }

        // SELECT ... FOR UPDATE 锁 costing_order（并发防护，同单切换串行化）
        CostingOrder co = em.find(CostingOrder.class, coid, LockModeType.PESSIMISTIC_WRITE);
        if (co == null) throw new BusinessException(404, "核价单不存在");
        if (!"PENDING".equals(co.status)) {
            throw new BusinessException(403, "仅待核价(PENDING)可切换版本，当前状态=" + co.status);
        }

        QuotationLineItem li = QuotationLineItem.findById(req.lineItemId);
        if (li == null) throw new BusinessException(404, "报价行不存在: " + req.lineItemId);
        Quotation q = Quotation.findById(li.quotationId);
        if (q == null || q.costingCardTemplateId == null) {
            throw new BusinessException(404, "核价模板不存在");
        }
        UUID templateId = q.costingCardTemplateId;

        // 校验 componentId 存在 + 是否为主树组件（bom_recursive_expand）
        boolean isTreeComponent = isTreeComponent(req.componentId);

        // ── upsert override + flush（先落库，让下面的重查读到最新覆盖）──────────────────
        CostingOrderVersionOverride ov = CostingOrderVersionOverride.find(coid, req.componentId, req.partNo);
        OffsetDateTime now = OffsetDateTime.now();
        if (ov == null) {
            ov = new CostingOrderVersionOverride();
            ov.costingOrderId = coid;
            ov.componentId = req.componentId;
            ov.partNo = req.partNo;
            ov.viewVersion = req.viewVersion;
            ov.createdAt = now;
            ov.updatedAt = now;
            ov.persist();
        } else {
            ov.viewVersion = req.viewVersion;
            ov.updatedAt = now;
        }
        em.flush();

        Map<UUID, Map<String, String>> overridesByComponent = loadOverridesByComponent(coid);

        // ── 重查 + 重装（scope 按 §E 规则）───────────────────────────────────────────
        Map<String, ArrayNode> baseRowsByComp;
        Set<String> affectedTabs = new LinkedHashSet<>();
        if (isTreeComponent) {
            // 主树切：该 line 各 driver 组件跑一次 $view（整卡重查），远程查询次数与料号数无关。
            Map<UUID, Map<String, ArrayNode>> rendered =
                    bomTreeRenderService.render(templateId, List.of(li), overridesByComponent);
            baseRowsByComp = rendered.getOrDefault(li.id, new LinkedHashMap<>());
            affectedTabs.addAll(driverComponentIdsOf(templateId));
        } else {
            // 非主树切：仅该组件 $view 跑一次（partNo 组限定），其余页签复用缓存 baseRows。
            baseRowsByComp = buildMixedBaseRows(co, li, q, req.componentId, req.partNo, overridesByComponent);
            affectedTabs.add(req.componentId.toString());
        }

        String newCostingCardValues = cardSnapshotService.buildCostingCardValues(
                li, templateId, q.customerId, q.id, null, null, baseRowsByComp);
        boolean hasTreeTab = cardSnapshotService.templateHasTreeTab(templateId);
        String newCostingExcelValues = cardSnapshotService.buildExcelValues(
                li, templateId, q.customerId, newCostingCardValues, hasTreeTab);

        // ── 写回 costing_render（仅受影响 line） + 重算 costing_total_amount ─────────────
        Map<String, RenderEntry> renderMap = parseRenderMap(co.costingRender);
        renderMap.put(li.id.toString(), new RenderEntry(newCostingCardValues, newCostingExcelValues));
        co.costingRender = serializeRenderMap(renderMap);
        co.costingTotalAmount = recomputeTotal(q.id, renderMap);
        // co 是 em.find 拿到的受管实体，事务提交时自动 flush（不显式 persist）

        VersionSwitchResponseDTO resp = new VersionSwitchResponseDTO();
        resp.lineItemId = li.id.toString();
        resp.costingCardValues = newCostingCardValues;
        resp.costingExcelColumns = newCostingExcelValues;
        resp.costingTotalAmount = co.costingTotalAmount;
        resp.affectedTabs = new ArrayList<>(affectedTabs);
        LOG.infof("[costing-version] switchVersion coid=%s line=%s comp=%s part=%s -> %s (tree=%s)",
                coid, li.id, req.componentId, req.partNo, req.viewVersion, isTreeComponent);
        return resp;
    }

    // =========================================================================
    // 私有工具
    // =========================================================================

    /** 该单全部 override，按 componentId 分组（partNo → viewVersion）。 */
    private Map<UUID, Map<String, String>> loadOverridesByComponent(UUID coid) {
        Map<UUID, Map<String, String>> out = new HashMap<>();
        for (CostingOrderVersionOverride ov : CostingOrderVersionOverride.findByCostingOrder(coid)) {
            out.computeIfAbsent(ov.componentId, k -> new HashMap<>()).put(ov.partNo, ov.viewVersion);
        }
        return out;
    }

    /** 该模板全部 driver 组件 id（字符串），供主树切的 affectedTabs。 */
    @SuppressWarnings("unchecked")
    private List<String> driverComponentIdsOf(UUID templateId) {
        List<Object> ids = em.createNativeQuery(
                        "SELECT DISTINCT c.id FROM template_component tc JOIN component c ON c.id = tc.component_id " +
                                "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("tid", templateId).getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : ids) if (o != null) out.add(o.toString());
        return out;
    }

    /**
     * 非主树切：从核价单缓存里取该 line 的旧 baseRows（未重查页签复用），只重跑
     * {@code componentId} 这一个组件（partNo 限定），替换其中命中 partNo 的行，保留同组件
     * 其他料号的行不动。缓存缺失该 line（罕见——理论上 createForSubmission 已为每行落缓存）时
     * 退化为「仅含刚重查组件」的 baseRows，其余页签暂缺（记 warn，不阻断）。
     */
    private Map<String, ArrayNode> buildMixedBaseRows(CostingOrder co, QuotationLineItem li, Quotation q,
                                                        UUID componentId, String partNo,
                                                        Map<UUID, Map<String, String>> overridesByComponent) {
        Map<String, RenderEntry> renderMap = parseRenderMap(co.costingRender);
        RenderEntry cached = renderMap.get(li.id.toString());
        Map<String, ArrayNode> baseRowsByComp;
        if (cached != null && cached.costingCardValues != null) {
            baseRowsByComp = cardSnapshotService.extractBaseRowsByComp(cached.costingCardValues);
        } else {
            LOG.warnf("[costing-version] line=%s 在 costing_render 缓存中缺失，非主树切退化为仅含当前组件",
                    li.id);
            baseRowsByComp = new LinkedHashMap<>();
        }

        List<ExpandDriverResponse.Row> freshRows = expandRows(componentId, q.customerId, partNo,
                overridesByComponent, BomTreeVarsContext.Mode.RENDER);

        // ★ repair-0590（料号切到"它没有的版本"后消失且不可恢复 = 本次根因）：
        //   切换料号的重查若 0 行，说明该 viewVersion 对此料号无数据（= 非该料号的可选版本，
        //   api.md §6 本应 400）。若继续（删旧行 + 无新行补），料号会从页签彻底消失，且 override
        //   已落库 → 每次渲染恒 0 行 → 无下拉可切回 → 永久丢失。故直接抛 400 中止：switchVersion 的
        //   @Transactional 回滚刚 upsert 的 override，料号原样保留；前端 message.error 提示（不静默）。
        boolean anyForPart = false;
        for (ExpandDriverResponse.Row r : freshRows) {
            if (partNo.equals(partNoOf(r.driverRow))) { anyForPart = true; break; }
        }
        if (!anyForPart) {
            String badVer = overridesByComponent.getOrDefault(componentId, java.util.Map.of()).get(partNo);
            throw new BusinessException(400, "料号 " + partNo + " 不存在版本 " + badVer
                    + " 的数据，无法切换到该版本（该版本非此料号的可选版本，切换将导致料号消失，已阻止）");
        }

        String cidStr = componentId.toString();
        ArrayNode merged = MAPPER.createArrayNode();
        ArrayNode old = baseRowsByComp.get(cidStr);
        if (old != null) {
            for (JsonNode rowNode : old) {
                String mn = partNoOf(rowNode);
                if (partNo.equals(mn)) continue; // 命中 partNo 的旧行整组丢弃，下面用新行替换
                merged.add(rowNode);
            }
        }
        for (ExpandDriverResponse.Row r : freshRows) {
            String mn = partNoOf(r.driverRow);
            if (!partNo.equals(mn)) continue; // 整视图取回后，只取本次切换的 partNo 那组新行
            ObjectNode rowNode = MAPPER.createObjectNode();
            rowNode.set("driverRow", MAPPER.valueToTree(r.driverRow));
            // ★ repair-071501（Bug2 切换后全「—」根因）：新行必须携带 expand 管线已算好的
            //   basicDataValues（key = {$view.列} path token），不能置空。核价页签字段多为
            //   BASIC_DATA（basic_data_path=$view.列），单元格从 basicDataValues 取值；
            //   置空 {} → 所有 BASIC_DATA 单元格解析失败显示「—」。与初次渲染
            //   （CardSnapshotService:1338-1342 snapshotRowNode）同一口径：driverRow+basicDataValues
            //   均直接来自 ExpandDriverResponse.Row。
            rowNode.set("basicDataValues",
                    r.basicDataValues != null ? MAPPER.valueToTree(r.basicDataValues) : MAPPER.createObjectNode());
            merged.add(rowNode);
        }
        baseRowsByComp.put(cidStr, merged);
        return baseRowsByComp;
    }

    /**
     * 统一走 BomTreeVarsContext + {@link ComponentDriverService#expandUncached} 的取行入口
     * （跳过 30s 缓存，见类注释）。<b>不</b>在 SQL 层用 {@code hf_part_no = ANY(:hfPartNos)} 收窄——
     * 组件 $view 的「本行归属料号」列名并不统一（cz_view/gx_view/zh_view 输出 {@code hf_part_no}；
     * pj_view/zpj_view 树组件、部分 ys_view 副本只输出 {@code material_no}，没有 {@code hf_part_no}
     * 列，SQL 层 {@code inner_q.hf_part_no = ANY(...)} 收窄会直接报列不存在）。
     *
     * <p>改用 {@code :total_material_no} 收窄（传 {@code [partNo]} 单元素数组）：pj_view 这类树
     * 组件的 $view 本身就要求 {@code component_no = ANY(:total_material_no)}（否则整表 0 行，见
     * {@code BomTreeRenderService} 同款契约），不传会让树组件的下拉/切换查询恒 0 行；对不引用
     * {@code :total_material_no} 的普通组件此参数无副作用（占位符未出现，安全忽略）。取回后仍在
     * Java 侧用 {@link #partNoOf(Map)}（hf_part_no 优先、退化 material_no）二次过滤兜底，双重防线。
     * 全程<b>一次</b>远程查询（禁 N+1 只约束查询次数，不约束单次返回行数）。
     */
    private List<ExpandDriverResponse.Row> expandRows(UUID componentId, UUID customerId, String partNo,
                                                   Map<UUID, Map<String, String>> overridesByComponent,
                                                   BomTreeVarsContext.Mode mode) {
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(
                null, List.of(partNo), overridesByComponent, mode));
        try {
            ExpandDriverResponse resp = componentDriverService.expandUncached(componentId, customerId);
            // 返回完整 Row（driverRow + basicDataValues），basicDataValues 是 BASIC_DATA 字段
            // 单元格取数的唯一来源，非树切换的 buildMixedBaseRows 必须原样保留（repair-071501 Bug2）。
            List<ExpandDriverResponse.Row> out = new ArrayList<>();
            if (resp != null && resp.rows != null) {
                for (ExpandDriverResponse.Row row : resp.rows) {
                    if (row != null && row.driverRow != null) out.add(row);
                }
            }
            return out;
        } finally {
            BomTreeVarsContext.clear();
        }
    }

    /** 行的「本行归属料号」：优先 hf_part_no（flat 组件标准键），退化 material_no（树/pj_view 等）。 */
    private static String partNoOf(Map<String, Object> driverRow) {
        Object v = driverRow.get("hf_part_no");
        if (v == null) v = driverRow.get("material_no");
        return v == null ? null : v.toString();
    }

    private static String partNoOf(JsonNode rowNode) {
        JsonNode driverRow = rowNode.path("driverRow");
        JsonNode v = driverRow.path("hf_part_no");
        if (v.isMissingNode() || v.isNull()) v = driverRow.path("material_no");
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    /** 版本号倒序比较：能解析为 long 就按数值比较，否则退化字符串比较（保证同长度数字串如 "2000"/"2001" 正确排序）。 */
    private static int compareVersionDesc(String a, String b) {
        try {
            return Long.compare(Long.parseLong(b), Long.parseLong(a));
        } catch (NumberFormatException e) {
            return b.compareTo(a);
        }
    }

    /** costing_render 缓存 JSON ↔ Map<lineItemId, RenderEntry> 互转。 */
    private static final class RenderEntry {
        final String costingCardValues;
        final String costingExcelValues;
        RenderEntry(String c, String e) { this.costingCardValues = c; this.costingExcelValues = e; }
    }

    private Map<String, RenderEntry> parseRenderMap(String costingRenderJson) {
        Map<String, RenderEntry> out = new LinkedHashMap<>();
        if (costingRenderJson == null || costingRenderJson.isBlank()) return out;
        try {
            JsonNode root = MAPPER.readTree(costingRenderJson);
            var it = root.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode v = e.getValue();
                String cardValues = v.path("costingCardValues").isNull() ? null : v.path("costingCardValues").asText(null);
                String excelValues = v.path("costingExcelValues").isNull() ? null : v.path("costingExcelValues").asText(null);
                out.put(e.getKey(), new RenderEntry(cardValues, excelValues));
            }
        } catch (Exception e) {
            LOG.warnf("[costing-version] parseRenderMap failed: %s", e.getMessage());
        }
        return out;
    }

    private String serializeRenderMap(Map<String, RenderEntry> map) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            for (Map.Entry<String, RenderEntry> e : map.entrySet()) {
                ObjectNode entry = root.putObject(e.getKey());
                entry.put("costingCardValues", e.getValue().costingCardValues);
                entry.put("costingExcelValues", e.getValue().costingExcelValues);
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[costing-version] serializeRenderMap failed: %s", e.getMessage());
            return "{}";
        }
    }

    /**
     * Σ 各行核价成本 subtotal × 年用量（不含 Step3 折扣）。renderMap 已含刚重算的那一行，
     * 其余行沿用缓存值——零额外远程查询（只需 annualVolume，走一次轻量 SELECT）。
     */
    private BigDecimal recomputeTotal(UUID quotationId, Map<String, RenderEntry> renderMap) {
        BigDecimal total = BigDecimal.ZERO;
        for (QuotationLineItem li : QuotationLineItem.<QuotationLineItem>list("quotationId", quotationId)) {
            RenderEntry entry = renderMap.get(li.id.toString());
            String cardValues = entry != null ? entry.costingCardValues : null;
            total = total.add(CostingSubtotalUtil.lineCostingAmount(cardValues, li.annualVolume));
        }
        return total.setScale(4, RoundingMode.HALF_UP);
    }
}
