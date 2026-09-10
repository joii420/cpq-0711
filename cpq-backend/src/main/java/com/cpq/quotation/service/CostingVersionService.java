package com.cpq.quotation.service;

import com.cpq.common.PrecisionPolicy;
import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import com.cpq.datasource.sqlview.TemplateRenderScope;
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
    private static final Set<String> PRECISION_RESPONSE_FIELDS = Set.of(
            "totalAmount", "costingTotalAmount", "amount", "subtotal",
            "unitPrice", "quantity", "rate");

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    CardSnapshotService cardSnapshotService;

    @Inject
    BomTreeRenderService bomTreeRenderService;

    @Inject
    com.cpq.template.service.PublishedTemplateReader publishedTemplateReader;

    // =========================================================================
    // B6：版本下拉（列出模式）
    // =========================================================================

    /**
     * 查询某料号在某页签的可选版本（api.md §2）。列出模式（{@code :versionFilter}→TRUE）+
     * partNo 限定，独立轻查、不走带缓存的 {@code expand}（守 AP-37 串号）。
     *
     * <p><b>树组件特例</b>（发现于实现期，非既定设计）：主树/子配件类组件的 $view 是「边」形态
     * （一行 = 一条 parent→child 边），其 {@code :total_material_no} 收窄谓词过滤的是<b>边的子端</b>
     * （{@code component_no}），而版本下拉问的是「这个 partNo <b>自己那张 BOM</b> 有哪些版本」——
     * 这个问题的答案落在<b>边的父端</b>（{@code production_no}），与 {@code total_material_no}
     * 的语义正交，通用的 {@code expandUncached} 整视图扫描答不了。故树组件走
     * {@link #treeVersionViewOf} 解析出的核价数据集全版本视图直查；非树（材质/工序/元素/组合工艺）
     * 组件走 {@code dsCostBaseTableOf} 专用查询或通用 $view 扫描路径。
     *
     * <p>⚠️ <b>repair-260910</b>：本方法的树分支原先硬编码查 V6 老表 {@code material_bom_item}
     * （{@code system_type='PRICING'} / {@code customer_no='_GLOBAL_'}）。核价数据迁到
     * {@code ds_cost_*} 数据集后老表不再接收新料号 ⇒ <b>候选集恒空</b>，而「当前版本」显示值来自
     * 骨架 SQL（已迁）⇒ 出现「能显示当前版本、却给不出候选」这种极具欺骗性的形态。
     * 🚫 <b>不要再把物理表名写死在这里</b>：视图名一律由组件方言解析（与骨架配置同源）。
     */
    public VersionOptionsResponseDTO listVersionOptions(UUID coid, UUID lineItemId, UUID componentId, String partNo) {
        CostingOrder co = CostingOrder.findById(coid);
        if (co == null) throw new BusinessException(404, "核价单不存在");
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) throw new BusinessException(404, "报价行不存在");
        Quotation q = Quotation.findById(li.quotationId);
        UUID customerId = q != null ? q.customerId : null;

        // task-0806 B19：模板渲染域，覆盖下方 isTreeComponent 对冻结快照的取值。
        UUID _tplPrev = TemplateRenderScope.open(q != null ? q.costingCardTemplateId : null);
        try {
            TreeSet<String> options = new TreeSet<>(CostingVersionService::compareVersionDesc);
            String isCurrentVersion = null; // is_current=true 对应的版本（override 缺失时的兜底 currentVersion）

            // task-260819 v9 · B-44④：非空 = 该组件的驱动视图是 builder 编译出来的 ds_cost_* 产物，
            // 走专用版本查询；null = V6 存量手写视图/报价侧，行为逐字不变（零回归）。
            //
            // repair-260910 B-1：树组件<b>不再</b>被短路成"无数据集"——它改由
            // {@link #treeVersionViewOf} 按<b>方言</b>解析出自己那套 BOM 全版本视图；
            // 非树分支的解析入口（dsCostBaseTableOf）与取值口径逐字不变（AC-11 门禁）。
            boolean tree = isTreeComponent(componentId);
            String treeVersionView = tree ? treeVersionViewOf(componentId) : null;
            String dsCostBase = tree ? null : dsCostBaseTableOf(componentId);

            if (tree) {
                // repair-260910 B-1（AC-3/4/5/10）：原实现硬编码查 V6 老表
                // material_bom_item(system_type='PRICING', customer_no='_GLOBAL_')，
                // 而核价数据早已迁到 ds_cost_* 数据集 ⇒ 候选集恒空（问题说明 ④ E-1：树内 7 个料号全 0 行）。
                //
                // 业务模型（用户 2026-09-10 定死）：BOM 一行 = 「父件 X 的第 N 版清单里包含子件 Y」，
                // 版本号描述的是 **X 那张清单** ⇒ 一个料号的版本 = 它<b>自己作为 production_no</b>
                // 的那些版本，与它挂在谁下面无关。
                // 🚫 不许按 component_no（子件）查：实测 300015 同挂 300012(v1) 与 300001(v2)，
                //    按子件查会给出它自己清单里并不存在的版本 2（违反 AC-4 / E-6，repair-0590 前科）。
                //
                // N+1：**一条** SQL。视图定义本身即「主表 UNION ALL _history」⇒ 历史版本 + 当前版本
                // 一次拿全，🚫 不要在 Java 侧再 UNION 一次；条数与版本数/行数/页签数均无关。
                if (treeVersionView != null) {
                    @SuppressWarnings("unchecked")
                    List<Object[]> rows = em.createNativeQuery(
                                    "SELECT version_no::text, is_current FROM " + treeVersionView +
                                            " WHERE production_no = :p AND version_no IS NOT NULL")
                            .setParameter("p", partNo).getResultList();
                    for (Object[] r : rows) {
                        if (r[0] == null) continue;
                        String v = r[0].toString();
                        options.add(v);
                        // is_current=true 那条 = override 缺失时的兜底 currentVersion，
                        // 与老表 is_current 语义一一对应（同构替换）。
                        if (r[1] instanceof Boolean b && b) isCurrentVersion = v;
                    }
                } else {
                    // 解析不到核价数据集（组件无 builder_config 的手写 $view / 报价方言）⇒ 无候选。
                    // 🚫 不回落 basic 视图（AC-10），🚫 不回落 V6 老表（那就是本次修的 bug）。
                    LOG.warnf("[costing-version] 树组件 %s 解析不到核价数据集方言，版本候选返回空列表"
                            + "（不回落 basic 视图、不回落 V6 老表）", componentId);
                }
            } else if (dsCostBase != null) {
                // task-260819 v9 · B-44④（D-86 / AC-124）：新 ds_cost_* 数据集的组件走**专用查询**，
                // 不再复用 Mode.LIST 跑整个页签视图 SQL。
                //
                // 🚫 为什么不能复用 LIST：applyFullScope 对 SUB/JOIN 目标节点也发 :versionFilter 宏，
                //    LIST 模式下宏整体展开成 TRUE ⇒ 锚点 N 个版本 × 被 JOIN 表 M 个版本 = 笛卡尔积。
                //    版本选项本身不会错（options 是 Set，view_version 只从锚点取），代价是**性能**，
                //    随 _history 增长线性恶化（实测 ds_cost_basic_material_bom 主表 14 行 / _history 47 行）。
                //    换成下面这条查询后，整个笛卡尔积问题消失 —— 两张表、一个轴列条件、零 JOIN。
                //
                // N+1：**一条** SQL 查完（主表 UNION 历史表），与版本数/行数/页签数均无关。
                String base = dsCostBase;
                @SuppressWarnings("unchecked")
                List<Object[]> vrows = em.createNativeQuery(
                                "SELECT version_no::text, true  AS is_cur FROM " + base
                                        + " WHERE production_no = :p AND version_no IS NOT NULL"
                                        + " UNION "
                                        + "SELECT version_no::text, false AS is_cur FROM " + base + "_history"
                                        + " WHERE production_no = :p AND version_no IS NOT NULL")
                        .setParameter("p", partNo).getResultList();
                for (Object[] r : vrows) {
                    if (r[0] == null) continue;
                    String v = r[0].toString();
                    options.add(v);
                    // 主表按定义只存当前版本（AC-118 不变量：同一轴值主表内 version_no 只有一个 distinct 值）
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
        } finally {
            TemplateRenderScope.restore(_tplPrev);
        }
    }

    /**
     * 组件是否为主树/子配件类（{@code bom_recursive_expand=true}）。task-0806 B19：改经
     * {@link com.cpq.template.service.PublishedTemplateReader} 取冻结快照，templateId 取自
     * {@link TemplateRenderScope}（调用方 {@code listVersionOptions}/{@code switchVersion} 均已在
     * 方法体外层 open 核价模板域）。componentId 不在该模板冻结页签中 → 抛 404（语义同旧实现的
     * "组件不存在"——旧实现查全局 component 表，本实现查"该核价模板引用的组件"，对正常调用路径
     * 等价，因为 componentId 恒来自该模板自身的页签）。
     */
    private boolean isTreeComponent(UUID componentId) {
        UUID templateId = TemplateRenderScope.currentTemplateId();
        if (templateId == null) {
            throw new BusinessException(500, "内部错误：模板渲染域未打开，无法判定组件类型 componentId=" + componentId);
        }
        for (com.cpq.template.entity.TemplateComponentSnapshot s : publishedTemplateReader.allTabsOf(templateId)) {
            if (componentId.equals(s.componentId)) {
                return Boolean.TRUE.equals(s.bomRecursiveExpand);
            }
        }
        throw new BusinessException(404, "组件不存在: " + componentId);
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
     *
     * <p><b>repair-260910 B-2</b>：树组件新增「叶子」守卫 —— 传入的料号在 BOM 里查不到以它为
     * {@code production_no} 的记录（= 它没有自己那张清单）时返回 <b>400</b>。既有的
     * <b>403</b>{@code 仅待核价(PENDING)可切换版本} 顺序与语义逐字不变。
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

        // task-0806 B17-a：模板渲染域，覆盖下方非主树分支 buildMixedBaseRows→expandRows→
        // componentDriverService.expandUncached（主树分支 bomTreeRenderService.render 已自带同款
        // open，此处对它是无害的嵌套 open，值相同）。
        UUID _tplPrev = TemplateRenderScope.open(templateId);
        try {
            // 校验 componentId 存在 + 是否为主树组件（bom_recursive_expand）
            boolean isTreeComponent = isTreeComponent(req.componentId);

            // ── repair-260910 B-2（AC-8）：树组件的「叶子」守卫 ─────────────────────────────
            //   叶子 = 该料号在 BOM 里查不到以它为 production_no 的记录 ⇒ 它没有自己那张清单
            //   ⇒ 没有版本可切。UI 侧本就不给叶子渲染下拉，本守卫防的是绕过 UI 直调接口：
            //   放过去只会写出一条永远不生效的 override 行（脏数据），且用户无从察觉。
            //   🚫 放在 PENDING 校验之后、upsert 之前——403 的既有语义与顺序逐字不变（AC-9 门禁）。
            //   ⚠️ 只对树组件生效：非树分支自有 repair-0590 的 0 行守卫，行为逐位不变（AC-11 门禁）。
            //   N+1：一条 SQL（EXISTS 探测），与版本数/行数无关；解析不到数据集时不设卡（存量行为）。
            if (isTreeComponent) {
                String treeVersionView = treeVersionViewOf(req.componentId);
                if (treeVersionView != null) {
                    @SuppressWarnings("unchecked")
                    List<Object> hit = em.createNativeQuery(
                                    "SELECT 1 FROM " + treeVersionView + " WHERE production_no = :p LIMIT 1")
                            .setParameter("p", req.partNo).getResultList();
                    if (hit.isEmpty()) {
                        throw new BusinessException(400, "料号 " + req.partNo
                                + " 没有自己的 BOM（在 " + treeVersionView
                                + " 中查不到以它为 production_no 的记录），不可切换版本");
                    }
                }
            }

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
            resp.costingCardValues = precisionSafeResponseJson(newCostingCardValues);
            resp.costingExcelColumns = precisionSafeResponseJson(newCostingExcelValues);
            resp.costingTotalAmount = co.costingTotalAmount;
            resp.affectedTabs = new ArrayList<>(affectedTabs);
            LOG.infof("[costing-version] switchVersion coid=%s line=%s comp=%s part=%s -> %s (tree=%s)",
                    coid, li.id, req.componentId, req.partNo, req.viewVersion, isTreeComponent);
            return resp;
        } finally {
            TemplateRenderScope.restore(_tplPrev);
        }
    }

    private String precisionSafeResponseJson(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = MAPPER.readTree(json);
            normalizePrecisionResponseFields(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException(500, "核价版本切换响应序列化失败: " + e.getMessage());
        }
    }

    private void normalizePrecisionResponseFields(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(this::normalizePrecisionResponseFields);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode object = (ObjectNode) node;
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode value = object.get(name);
            if (PRECISION_RESPONSE_FIELDS.contains(name) && value != null && value.isNumber()) {
                object.put(name, PrecisionPolicy.toPlainDecimalString(value.decimalValue()));
            } else {
                normalizePrecisionResponseFields(value);
            }
        }
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

    /**
     * 该模板全部 driver 组件 id（字符串，去重），供主树切的 affectedTabs。task-0806 B19：改经
     * {@link com.cpq.template.service.PublishedTemplateReader#driverCompsOf} 取冻结快照，不再直读
     * 活 {@code component}/{@code template_component} 表。{@code DISTINCT} 去重语义改内存
     * {@code LinkedHashSet}（同 componentId 可能因多实例挂多个 template_component 行）。
     */
    private List<String> driverComponentIdsOf(UUID templateId) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (com.cpq.template.entity.TemplateComponentSnapshot s : publishedTemplateReader.driverCompsOf(templateId)) {
            if (s.componentId != null) seen.add(s.componentId.toString());
        }
        return new ArrayList<>(seen);
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

    // =========================================================================
    // task-260819 v9 · B-44④（D-86 / AC-124）：ds_cost_* 组件的专用版本源解析
    // =========================================================================

    /** 编译器产物的 FROM 源（{@code SemanticCompiler#compile} 恒在行首输出 {@code FROM <物理源> <别名>}）。 */
    private static final java.util.regex.Pattern FROM_LINE =
            java.util.regex.Pattern.compile("(?m)^FROM\\s+(\\S+)");
    /** 只认 v9 核价两套的全版本视图；顺带把表名限死成白名单形态，杜绝任何拼接注入。 */
    private static final java.util.regex.Pattern COST_ALL_VIEW =
            java.util.regex.Pattern.compile("^v_(ds_cost_(?:basic|detail)_[a-z0-9_]+)_all$");

    /**
     * 该组件的驱动 SQL 视图是不是 builder 编译出来的 {@code ds_cost_*} 产物？是则返回它的<b>主表名</b>
     * （如 {@code ds_cost_basic_material_bom}），否则返回 {@code null}。
     *
     * <p><b>为什么按 SQL 文本的 FROM 源判定，而不是解析 builder_config</b>：{@code builder_config}
     * 里<b>没有</b>方言字段（{@code BuilderConfig#dialect} 的注释自陈"仅 POST /compile 消费，不是
     * 持久化字段"），单靠 {@code tabType/variantKey} 反查语义图还要再决定查哪个 dialect —— 等于把
     * 一个已经写死在产物里的事实重新猜一遍。编译器的 {@code FROM <物理源>} 是**行首固定输出**，
     * 而 {@code physical_table} 正是 V410 种子写进去的 {@code v_<主表>_all}，这是最短且唯一的真源。
     *
     * <p><b>失败即回退</b>：匹配不上（V6 存量手写视图 / 报价侧 / 组件没有 builder 视图）一律返回
     * {@code null}，调用方走原来的 {@code Mode.LIST} 路径，存量行为逐字不变。
     *
     * <p>N+1：一条 SQL，与组件数/版本数无关。
     */
    private String dsCostBaseTableOf(UUID componentId) {
        @SuppressWarnings("unchecked")
        List<String> tpls = em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view "
                        + "WHERE component_id = :cid AND builder_config IS NOT NULL")
                .setParameter("cid", componentId).getResultList();
        for (String tpl : tpls) {
            if (tpl == null) continue;
            java.util.regex.Matcher m = FROM_LINE.matcher(tpl);
            if (!m.find()) continue;
            java.util.regex.Matcher v = COST_ALL_VIEW.matcher(m.group(1));
            if (v.matches()) return v.group(1);
        }
        return null;
    }

    // =========================================================================
    // repair-260910 B-1 / B-2：树组件的 BOM 版本源解析（按方言，与骨架配置同源）
    // =========================================================================

    /**
     * 树组件的「BOM 全版本视图」——{@code 主表 UNION ALL _history}，按<b>数据集方言</b>分档。
     *
     * <p><b>为什么按方言而不是按组件自己视图的 FROM 文本</b>：版本下拉的候选<b>必须与「版本」列的
     * 显示值同源</b>，而显示值来自骨架 SQL（{@code costing_bom_tree_config.sql_template}），骨架
     * 走哪一条又是由 {@code BomTreeRenderService#resolveSkeletonUsage} 按<b>同一个</b>
     * {@code builder_config ->> 'dialect'} 决定的。两处共用同一个判据 ⇒ 同源是可证的；
     * 若改按 FROM 文本推，两者就成了两个事实来源，能各自漂移且不报错。
     *
     * <p><b>解析不到就返回 {@code null}</b>（组件无 {@code builder_config} 的手写 $view / 报价方言 /
     * 方言值编译器不认）⇒ 调用方返回空候选。🚫 <b>不回落 basic 视图</b>（AC-10：回落的失败形态是
     * 「详细核价悄悄用了基础核价的版本列表」，能渲染、不报错、数据是别的数据集的），
     * 🚫 <b>更不回落 V6 老表</b>（那正是本次修掉的 bug）。
     *
     * <p>N+1：一条 SQL，与版本数/行数/页签数无关。
     */
    private String treeVersionViewOf(UUID componentId) {
        String dialect;
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                            "SELECT csv.builder_config ->> 'dialect' FROM component_sql_view csv "
                                    + "WHERE csv.component_id = :cid AND csv.status = 'ACTIVE' "
                                    + "AND csv.builder_config IS NOT NULL "
                                    + "AND (csv.builder_config ->> 'dialect') IS NOT NULL "
                                    + "ORDER BY csv.updated_at DESC NULLS LAST LIMIT 1")
                    .setParameter("cid", componentId).getResultList();
            dialect = rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
        } catch (Exception e) {
            LOG.warnf("[costing-version] 读取树组件 %s 的方言失败（%s），版本候选按空处理",
                    componentId, e.getMessage());
            return null;
        }
        return treeVersionViewOfDialect(dialect);
    }

    /**
     * {@link #treeVersionViewOf} 的<b>纯函数内核</b>（包级可见仅为可测：它决定「查哪个数据集的版本」，
     * 判错了不会报错、只会给出另一个数据集的候选）。
     *
     * <p>返回值恒为常量字面量之一或 {@code null}，<b>不含任何外部输入</b> ⇒ 调用方拼接进 SQL 是安全的。
     *
     * @param dialectOrNull {@code component_sql_view.builder_config ->> 'dialect'}
     * @return {@code COST_BASIC → v_ds_cost_basic_material_bom_all}、
     *         {@code COST_DETAIL → v_ds_cost_detail_material_bom_all}；
     *         其余（{@code QUOTE} / null / 空 / 不可识别）一律 {@code null}
     */
    static String treeVersionViewOfDialect(String dialectOrNull) {
        // 🚫 不能直接调 CompileDialect.parse：它对 null/空<b>静默返回 QUOTE</b>（"不传 = 报价侧"），
        //    那会把「解析不到」变成「报价侧」，进而变成一个我们答不上来的问题被当成答上了。
        if (dialectOrNull == null || dialectOrNull.isBlank()) return null;
        com.cpq.builder.compiler.CompileDialect d;
        try {
            d = com.cpq.builder.compiler.CompileDialect.parse(dialectOrNull);
        } catch (Exception e) {
            LOG.warnf("[costing-version] builder_config.dialect=「%s」无法识别（%s），版本候选按空处理",
                    dialectOrNull, e.getMessage());
            return null;
        }
        return switch (d) {
            case COST_BASIC -> "v_ds_cost_basic_material_bom_all";
            case COST_DETAIL -> "v_ds_cost_detail_material_bom_all";
            // 报价侧的树不走核价版本切换（本服务只挂 costing-order 端点），无候选。
            case QUOTE -> null;
        };
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
        // task-0810：落库工作值统一规整到 12 位。
        return PrecisionPolicy.roundForCalculation(total);
    }
}
