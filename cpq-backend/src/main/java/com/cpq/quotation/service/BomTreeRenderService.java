package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.CostingBomTreeConfig;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import com.cpq.datasource.sqlview.TemplateRenderScope;
import com.cpq.datasource.sqlview.VersionFilterMacro;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.template.service.PublishedTemplateReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 核价单全量递归 + 按料号分组渲染重构（Task 2.2）—— 整单编排入口。
 *
 * <p>流程（整单一次，不再逐行/逐料号展开）：
 * <ol>
 *   <li>收集整单料号种子（各 {@link QuotationLineItem#productPartNoSnapshot}，去重）；</li>
 *   <li>跑当前生效的全局递归 SQL（{@link CostingBomTreeConfig#findActive()}），一次性拿到全单闭包
 *       （5 列 {@code root_no/material_no/bom_version/parent_no/node_path}）；</li>
 *   <li>纯函数分组建树（{@link CostingTreeGrouping#group}）：按根聚出每张卡片的料号集合 +
 *       全单料号并集 + 每张卡片的 spine 树行；</li>
 *   <li>对模板每个 driver 组件（{@code data_driver_path} 非空）跑一次其 $view（用全单
 *       {@code total_material_no} 收窄，替代逐行/逐料号展开），按 {@code material_no} 分桶；</li>
 *   <li>逐卡逐组件装配 baseRows：普通页签把该卡片料号集合命中的行平铺；树页签（勾选了
 *       {@code bom_recursive_expand} 的组件）以该卡片的 spine 节点为行主轴，缺数据补空行；</li>
 *   <li>返回 {@code lineItemId → componentId → baseRows}，供 {@code CardSnapshotService} 后续走
 *       公式计算（接线见 Task 3.1，<b>不在本类范围</b>）。</li>
 * </ol>
 *
 * <p><b>单模板假设</b>：本方法按<b>单一 {@code templateId}</b> 查询 driver 组件清单（与既有
 * {@code CardSnapshotService#expandTemplateDriverBaseRows(UUID templateId, ...)} 同款签名/假设一致）。
 * 若一张报价单跨多个模板，调用方需按 {@code templateId} 分组后逐组调用本方法（Task 3.1 决定）。
 *
 * <p><b>契约回顾</b>：递归 SQL 输入 {@code :production_part_nos}（text[]），页签 SQL（组件 $view）
 * 输入 {@code :total_material_no}（text[]）、输出必含 {@code material_no} 列。匹配键仅
 * {@code material_no}；落选行（{@code material_no} 不属任何卡）丢弃；同料号多 occurrence 保留；
 * 树页签 = 勾了 {@code bom_recursive_expand} 的组件。
 */
@ApplicationScoped
public class BomTreeRenderService {

    private static final Logger LOG = Logger.getLogger(BomTreeRenderService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DataSource dataSource;

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    PublishedTemplateReader publishedTemplateReader;

    /**
     * task-0721 B3/B4/B6/B7 单一收口点（2026-07-21 业务方裁决 Q1）：判定某组件是否为
     * 「报价侧 BOM 树页签」。
     *
     * <p><b>判据 = {@code component.tabType == "BOM"}</b>，与既有 {@code bomRecursiveExpand}
     * （核价侧递归展开开关）<b>无关</b>——后者是组件级全局开关，同一组件被多模板共用时一开全生效；
     * B4 保存组件时会按 {@code tabType} 自动同步 {@code bomRecursiveExpand}（{@code tabType="BOM"}
     * → 置 true；改为其他值 → 置 false），但那只是实现细节 / 兼容既有 UI 展示，<b>不参与本判断</b>。
     *
     * <p>全链路（B3 物化路由 / B6 加叶子 / B7 删除级联）只应调用本方法判定"是否走树渲染"，
     * 不要在别处散落重复的 {@code "BOM".equals(...)} / {@code bomRecursiveExpand} 判断。
     *
     * <p>核价侧路由判据不受影响：{@code CardSnapshotService#templateHasTreeTab} 仍按
     * {@code bomRecursiveExpand=true} 判定（核价侧现有行为逐位不变，AC-10 零回归门禁）。
     */
    public static boolean isQuoteTreeTabType(String tabType) {
        return "BOM".equals(tabType);
    }

    /**
     * task-260819 B-19/B-21（D-52/D-56）产物：整单 BOM 料号并集 + 「后代→根」映射，同一次树遍历
     * 产出，不为映射再查一次（B-21 硬约束②）。
     */
    public static final class MaterialUnionResult {
        /** 整单 BOM 料号并集（含各根自身），供 {@code :total_material_no} 绑定（AC-58）。 */
        public final List<String> totalMaterialNo;
        /**
         * 料号 → 归属的根成品料号列表。一个子件可能同时属于多个根（两个成品共用同一子件时，
         * AC-62③ 要求该子件行必须同时出现在各自根的桶里，各自独立、不互相吞掉）——因此每个
         * 料号对应的是 {@code List<String>} 而非单值。查不到的料号（不属于本次任何根的 BOM
         * 闭包）不会出现在本 map 里，消费方按 B-21 约定的兜底语义自行处理（按其自身入桶）。
         */
        public final Map<String, List<String>> rootsByMaterial;
        /**
         * task-260819 B+（D-58）：根成品料号 → 它自己的 BOM 闭包（含自身）。{@code g.cardMaterialNo}
         * 原样带出（已是 root→该根下全部料号 的形状），供 {@code ComponentDriverService} 单料号
         * {@code loadByPath} 调用点加宽 outer {@code hfPartNos} 用——🚨 只能用这个，不能用
         * {@code totalMaterialNo}（那是整单池，单卡路径没有回分步骤，会把别的成品的行带进来）。
         */
        public final Map<String, List<String>> materialsByRoot;

        MaterialUnionResult(List<String> totalMaterialNo, Map<String, List<String>> rootsByMaterial,
                             Map<String, List<String>> materialsByRoot) {
            this.totalMaterialNo = totalMaterialNo;
            this.rootsByMaterial = rootsByMaterial;
            this.materialsByRoot = materialsByRoot;
        }
    }

    /**
     * task-260819 B-19/B-21：整单（或调用方传入的这几行）BOM 料号并集，复用与 {@link #renderInternal}
     * 完全相同的口径（seed 去重 → {@link CostingBomTreeConfig#findActive(String)} 生效配置 → 递归
     * CTE {@link #queryRecursive} → {@link CostingTreeGrouping#group}），不另写第二套算法（D-50 要
     * 收敛的正是这个）。
     *
     * <p>调用惯例延续 {@link #render}：传几行就按这几行算——单行调用（{@code List.of(li)}）算的是
     * 「这一行自己的 BOM 闭包」，批量调用（整单 {@code lines}）算的是整单并集；本方法与 {@code render}
     * 一样，每次调用只发一次递归 SQL（N+1 约束②：SQL 条数是常数，与传入的行数无关）。
     *
     * @param lineItems 本次要算并集的报价行（只读 {@code productPartNoSnapshot} 字段，轻量携带体亦可）
     * @param usage     {@code CostingBomTreeConfig} 的 usage 维度（报价侧固定传 {@code "QUOTE"}）
     * @return 种子为空时两个字段均为空集合（非 null）
     */
    public MaterialUnionResult collectTotalMaterialNoUnion(List<QuotationLineItem> lineItems, String usage) {
        LinkedHashSet<String> seed = new LinkedHashSet<>();
        if (lineItems != null) {
            for (QuotationLineItem li : lineItems) {
                if (li == null) continue;
                String root = li.productPartNoSnapshot;
                if (root == null || root.isBlank()) continue;
                seed.add(root);
            }
        }
        if (seed.isEmpty()) {
            return new MaterialUnionResult(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        String effUsage = (usage == null || usage.isBlank()) ? "COSTING" : usage;
        CostingBomTreeConfig cfg = CostingBomTreeConfig.findActive(effUsage);
        if (cfg == null) {
            throw new BusinessException(400, "未配置生效的" + ("QUOTE".equals(effUsage) ? "报价" : "核价")
                    + "树递归 SQL（costing_bom_tree_config 无 usage=" + effUsage + " 且 isActive=true 记录）");
        }
        List<CostingTreeNode> rows;
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(new ArrayList<>(seed), null));
        try {
            rows = queryRecursive(cfg.sqlTemplate, new ArrayList<>(seed), java.util.Collections.emptyMap());
        } finally {
            BomTreeVarsContext.clear();
        }
        CostingTreeGrouping.Result g = CostingTreeGrouping.group(rows);

        // 「后代→根」映射：g.cardMaterialNo 是 root -> 该根下全部料号（含根自身）的集合，
        // 本处反向展开——同一料号出现在多个根的集合里时，rootsByMaterial 里就是多值 List
        // （AC-62③ 不串单的数据结构基础）。纯内存反转，不触发任何额外查询（N+1 约束②）。
        Map<String, List<String>> rootsByMaterial = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : g.cardMaterialNo.entrySet()) {
            String root = e.getKey();
            for (String mat : e.getValue()) {
                rootsByMaterial.computeIfAbsent(mat, k -> new ArrayList<>()).add(root);
            }
        }
        // 「根→自身闭包」：g.cardMaterialNo 本身就是这个形状(root -> LinkedHashSet<material>)，
        // 转成 List<String> 即可直接用，不需要再遍历一次树（同一次 group() 结果两处消费，N+1 约束②）。
        Map<String, List<String>> materialsByRoot = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : g.cardMaterialNo.entrySet()) {
            materialsByRoot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return new MaterialUnionResult(new ArrayList<>(g.totalMaterialNo), rootsByMaterial, materialsByRoot);
    }

    /**
     * 整单渲染入口。
     *
     * @param templateId 本组 line items 共用的模板 ID（见类注释「单模板假设」）
     * @param lineItems  本组报价单行（同一 templateId）
     * @return {@code lineItemId → (componentId(字符串) → baseRows)}；无有效料号种子时返回空 Map。
     */
    public Map<UUID, Map<String, ArrayNode>> render(UUID templateId, List<QuotationLineItem> lineItems) {
        return render(templateId, lineItems, null);
    }

    /**
     * task-0713 B3 版本感知重载：{@code overridesByComponent} 非空时，对含 {@code :versionFilter}
     * 宏的组件 $view / 主树递归 SQL 应用该核价单已保存的版本 override（componentId → partNo →
     * viewVersion）。{@code null}/空 = 零行为变化（宏展开后 override 数组为空 → 恒退化为
     * {@code is_current}，与两参重载逐位等价）。
     *
     * <p>task-0721 B2：委派 4 参重载，{@code usage} 固定传 {@code "COSTING"}——<b>核价侧全部既有
     * 调用点零改动，行为逐位不变</b>（AC-10 零回归门禁）。报价侧新调用点须显式调 4 参重载传
     * {@code "QUOTE"}（见 {@code ConfigureSnapshotService}）。
     */
    public Map<UUID, Map<String, ArrayNode>> render(UUID templateId, List<QuotationLineItem> lineItems,
                                                     Map<UUID, Map<String, String>> overridesByComponent) {
        return render(templateId, lineItems, overridesByComponent, "COSTING");
    }

    /**
     * task-0721 B2：4 参重载 —— {@code usage} 决定按哪个维度取生效的递归 SQL 配置
     * （{@link CostingBomTreeConfig#findActive(String)}）。{@code QUOTE}=报价侧独立配置口径；
     * {@code COSTING}=核价侧现役配置（与改造前 {@code findActive()} 逐位等价，因存量配置已
     * {@code DEFAULT 'COSTING'} 迁移，见 V346）。
     */
    public Map<UUID, Map<String, ArrayNode>> render(UUID templateId, List<QuotationLineItem> lineItems,
                                                     Map<UUID, Map<String, String>> overridesByComponent,
                                                     String usage) {
        // ── task-0729（2026-08-03）：在此统一兜底设置 QuotationIdContext ──────────────────
        // 背景：`:customerCode` 的解析走 QuotationIdContext(ThreadLocal) → DataLoader →
        //   RuntimeContext.toNamedParams() 这条独立管线，与 render() 内部 expandUncached 的
        //   customerId 入参无关（那个传 null 是有意为之，见 §④ 注释）。
        //   实测 6 个直接触发 render() 的调用方中，4 个未设置该上下文，且全在核价侧：
        //     snapshotCostingSideOnly / refreshCostingCardValues /
        //     refreshCostingCardValuesForLine（本任务 B0 的 S5）/ CostingVersionService.switchVersion
        //   → 挂在含树页签模板下、且 SQL 用到 :customerCode 的 $view（如 wl_ys_bom_view 的
        //     元素单价）永远解析不到客户，取价恒为 null。详见同目录
        //     `dev-docs/task-0729-.../BomTreeRenderService-customerId影响面评估.md`。
        // 🔒 必须用「保存-恢复」而非无脑 set+clear：QuotationIdContext.clear() 是无条件
        //   CURRENT.remove()，无引用计数、不支持重入。若内层无脑 clear，会把外层调用方
        //   （如 ConfigureSnapshotService.snapshotLines，它自己已 set）的上下文一并清掉。
        if (lineItems == null || lineItems.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // task-0806 B17-a：模板渲染域，覆盖下方 renderInternal 内 componentDriverService.expandUncached
        // 调用点，让 ComponentDriverService.setNested 能拿到真实 templateId（原恒传 null）。本方法是
        // BOM 树渲染唯一入口，templateId 就是其显式参数，故直接在此包一层，renderInternal 本身无需改动。
        UUID prevTemplateId = TemplateRenderScope.open(templateId);
        try {
            UUID ctxQuotationId = lineItems.get(0).quotationId;
            if (ctxQuotationId == null) {
                return renderInternal(templateId, lineItems, overridesByComponent, usage);
            }
            UUID prevQuotationId = QuotationIdContext.get();
            QuotationIdContext.set(ctxQuotationId);
            try {
                return renderInternal(templateId, lineItems, overridesByComponent, usage);
            } finally {
                if (prevQuotationId != null) {
                    QuotationIdContext.set(prevQuotationId);   // 恢复外层，而不是清空
                } else {
                    QuotationIdContext.clear();
                }
            }
        } finally {
            TemplateRenderScope.restore(prevTemplateId);
        }
    }

    /**
     * 原 render 主体；除下方 §④ 的 customerId 真根因修复外，行为逐字未变；context 管理已上提到
     * 同名 public 包装方法。
     */
    private Map<UUID, Map<String, ArrayNode>> renderInternal(UUID templateId, List<QuotationLineItem> lineItems,
                                                     Map<UUID, Map<String, String>> overridesByComponent,
                                                     String usage) {
        Map<UUID, Map<String, ArrayNode>> out = new LinkedHashMap<>();
        if (lineItems == null || lineItems.isEmpty()) {
            return out;
        }
        // task-0729（2026-08-03）真根因修复：临时 DEBUG 日志实测确认 :customerCode 100% 依赖本方法
        // §④ 传给 expandUncached 的 customerId 入参本身（QuotationIdContext 只解析 :quotationId/
        // :priceBaseDate，对 :customerCode 无任何帮助——之前的假设已被证伪）。整单只需查一次。
        UUID ctxCustomerId = null;
        UUID _seedQid = lineItems.get(0).quotationId;
        if (_seedQid != null) {
            Quotation _q = Quotation.findById(_seedQid);
            if (_q != null) ctxCustomerId = _q.customerId;
        }
        Map<UUID, Map<String, String>> overrides =
                (overridesByComponent != null) ? overridesByComponent : java.util.Collections.emptyMap();

        // ① 整单料号种子 + root_no -> lineItemId 反查（同料号可能被多个 line item 共用）
        LinkedHashSet<String> seed = new LinkedHashSet<>();
        Map<String, List<UUID>> rootToLineItemIds = new LinkedHashMap<>();
        for (QuotationLineItem li : lineItems) {
            String root = li.productPartNoSnapshot;
            if (root == null || root.isBlank()) {
                continue;
            }
            seed.add(root);
            rootToLineItemIds.computeIfAbsent(root, k -> new ArrayList<>()).add(li.id);
        }
        if (seed.isEmpty()) {
            return out;
        }

        // ②-pre 模板 driver 组件清单（提前到递归查询之前，供「主树」组件 override 查找 + §④ 复用，
        // 避免重复查询）。
        // task-0806 B18：原实现「照抄 CardSnapshotService#expandTemplateDriverBaseRows 的既有查询」
        // 与 FR-5 已收口的 5 处同形，却漏列在清单里（B17 排查时发现）。改为与
        // ConfigureSnapshotService#loadDriverComponents 同款分支写法：PUBLISHED/ARCHIVED 走
        // PublishedTemplateReader 冻结快照，DRAFT（或模板查不到）保留活表查询（§5.1.2 冻结边界，
        // DRAFT 不写快照）。结果统一适配回 Object[]{componentId, bomRecursiveExpand} 形状，
        // 使下方 §④ 复用同一 driverComps 的逻辑零改动。
        Template _bt18Tpl = Template.findById(templateId);
        String _bt18Status = (_bt18Tpl != null) ? _bt18Tpl.status : null;
        List<Object[]> driverComps;
        if ("PUBLISHED".equals(_bt18Status) || "ARCHIVED".equals(_bt18Status)) {
            List<Object[]> viaReader = new ArrayList<>();
            Set<UUID> seenDriverIds = new HashSet<>();   // 与旧 SQL 的 DISTINCT c.id 同语义
            for (TemplateComponentSnapshot s : publishedTemplateReader.driverCompsOf(templateId)) {
                if (!seenDriverIds.add(s.componentId)) continue;
                viaReader.add(new Object[]{s.componentId, s.bomRecursiveExpand});
            }
            driverComps = viaReader;
            LOG.debugf("[task-0806 B18] driverComps via PublishedTemplateReader templateId=%s status=%s count=%d",
                    templateId, _bt18Status, driverComps.size());
        } else {
            @SuppressWarnings("unchecked")
            List<Object[]> viaActiveTable = em.createNativeQuery(
                            "SELECT DISTINCT c.id, c.bom_recursive_expand FROM template_component tc " +
                                    "JOIN component c ON c.id = tc.component_id " +
                                    "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                    .setParameter("tid", templateId)
                    .getResultList();
            driverComps = viaActiveTable;
            LOG.debugf("[task-0806 B18] driverComps via activeTable(DRAFT) templateId=%s status=%s count=%d",
                    templateId, _bt18Status, driverComps.size());
        }
        UUID treeComponentId = null;
        for (Object[] dc : driverComps) {
            if (dc != null && dc[0] != null && (dc[1] instanceof Boolean b) && b) {
                treeComponentId = UUID.fromString(dc[0].toString());
                break;
            }
        }
        Map<String, String> treeOverrides = (treeComponentId != null)
                ? overrides.getOrDefault(treeComponentId, java.util.Collections.emptyMap())
                : java.util.Collections.emptyMap();

        // ② 当前 usage 维度生效的递归 SQL 配置（task-0721 B2：按 usage 取，核价/报价互不干扰）
        String effUsage = (usage == null || usage.isBlank()) ? "COSTING" : usage;
        CostingBomTreeConfig cfg = CostingBomTreeConfig.findActive(effUsage);
        if (cfg == null) {
            throw new BusinessException(400, "未配置生效的" + ("QUOTE".equals(effUsage) ? "报价" : "核价")
                    + "树递归 SQL（costing_bom_tree_config 无 usage=" + effUsage + " 且 isActive=true 记录）");
        }

        List<CostingTreeNode> rows;
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(new ArrayList<>(seed), null, overrides));
        try {
            rows = queryRecursive(cfg.sqlTemplate, new ArrayList<>(seed), treeOverrides);
        } finally {
            BomTreeVarsContext.clear();
        }

        // ③ 纯函数分组建树
        CostingTreeGrouping.Result g = CostingTreeGrouping.group(rows);

        // ④ 每个 driver 组件跑一次其 $view，按 material_no 分桶；同时记录哪些组件是树页签。
        // BomTreeVarsContext 携带整卡 overridesByComponent，SqlViewExecutor 按当前
        // SqlViewRuntimeContext.componentId 在绑定期精确解析出「这一个组件」的 override 切片
        // （见 SqlViewExecutor#injectCostingTreeVars），故此处仍可一次 set/clear 覆盖整个循环。
        Map<String, Map<String, List<ExpandDriverResponse.Row>>> rowsByCompThenMaterial = new LinkedHashMap<>();
        Set<String> treeTabCompIds = new HashSet<>();
        // task-0729 debug（2026-08-03）真根因修复 #2：区分「合法 0 行」与「expand 异常导致的 0 行」。
        // 原先 §④ 每组件 catch(Exception) 只打 WARN、囫囵吞掉——render() 照常返回、上游 upgrade()
        // 照常报 SUCCESS，但该组件（乃至整卡片）的业务行已经悄悄清零。实测这类异常在 job 执行器路径下
        // 100% 可复现（ContextNotActiveException，见 executeItem 的 @ActivateRequestContext 修复），
        // 却因为这个 catch 连续躲过 8 轮排查。现在：异常仍然逐组件捕获（不因一个组件失败打断其余组件
        // 的展开循环），但循环结束后若有任何组件真正抛过异常，整体 render() 必须失败退出，不能带着
        // 部分组件的空数据冒充成功。
        Map<String, String> failedComponents = new LinkedHashMap<>();
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, g.totalMaterialNo, overrides));
        try {
            for (Object[] dc : driverComps) {
                if (dc == null || dc[0] == null) {
                    continue;
                }
                String cidStr = dc[0].toString();
                // 默认/非布尔 -> true（与 expandTemplateDriverBaseRows 同款兜底约定）
                boolean recursive = !(dc[1] instanceof Boolean) || (Boolean) dc[1];
                if (recursive) {
                    treeTabCompIds.add(cidStr);
                }
                UUID compId = UUID.fromString(cidStr);
                // 分桶键语义按组件类型不同：树页签 = (parent_no, material_no) 边键；普通页签 = material_no。
                Map<String, List<ExpandDriverResponse.Row>> byKey = new LinkedHashMap<>();
                try {
                    // 见类注释「跑组件 $view 的入口」说明：partNo/partVersion/lineItemId 继续传 null，
                    // 让 SqlViewExecutor 从 BomTreeVarsContext 拿 :total_material_no 收窄，
                    // 不再靠 partNo/lineItemId 维度过滤（这条 $view 对整单只跑一次）。
                    // 🔴 task-0729 真根因修复：customerId 改传上面查到的 ctxCustomerId（原先这里也传
                    // null，是 :customerCode 在核价侧 4 条路径下恒解析不到值的直接原因——DEBUG 日志
                    // 实测确认 QuotationIdContext 对 :customerCode 无效，唯一来源就是这个入参）。
                    // 用 expandUncached（Task 3.1 事项A）而非 expand：该方法"仅跳过缓存读写、语义与
                    // 9-arg expand 完全相同"（见 ComponentDriverService 类注释），即本次改 customerId
                    // 传值不影响 AP-37 缓存维度问题——这条路径本就完全绕开 expandCache，不存在串号风险。
                    ExpandDriverResponse resp = componentDriverService.expandUncached(compId, ctxCustomerId);
                    if (resp != null && resp.rows != null) {
                        int total = 0;
                        int kept = 0;
                        int missingParent = 0;
                        for (ExpandDriverResponse.Row r : resp.rows) {
                            if (r == null || r.driverRow == null) {
                                continue;
                            }
                            total++;
                            Object mn = r.driverRow.get("material_no");
                            if (mn == null) {
                                continue; // 落选行（无 material_no）丢弃
                            }
                            kept++;
                            if (recursive) {
                                // 树页签：按 (parent_no, material_no) 边键分桶，让每个树节点只挂到
                                // 它自己那条「父→子」边的业务行（同一子件挂多父时不再重复/挂错父）。
                                Object pn = r.driverRow.get("parent_no");
                                if (pn == null) missingParent++;
                                byKey.computeIfAbsent(
                                        edgeKey(pn == null ? null : pn.toString(), mn.toString()),
                                        k -> new ArrayList<>()).add(r);
                            } else {
                                // 普通页签：按 material_no 料号维度分桶（不变）。
                                byKey.computeIfAbsent(mn.toString(), k -> new ArrayList<>()).add(r);
                            }
                        }
                        if (total > 0 && kept == 0) {
                            LOG.warnf("[costing-tree] 组件 %s 的 $view 返回 %d 行但无有效 material_no"
                                            + "（可能未输出 material_no 列），该页签数据全部落选",
                                    cidStr, total);
                        }
                        // repair-0814 D-3（原 BL-0172）：原先这里只 LOG.warnf，渲染照常返回 200，
                        // 该页签渲染成满屏空行而用户侧零提示。改为显式失败——与本方法下方
                        // failedComponents 块「不能带着残缺数据静默"成功"」的既定口径统一。
                        //
                        // 触发条件保持不变，不得放宽：kept > 0 且【全部】行都没有父件列 = 配置错误
                        // （树页签 $view 漏输出 parent_no 列）。部分行缺 parent_no 不在此拦——那是
                        // 数据问题不是配置问题，原样放行以免误伤。
                        //
                        // 强度依据（2026-08-14 全库扫描 cpq_db_0724）：18 个 bom_recursive_expand=true
                        // 的组件，其 component_sql_view.sql_template 全部含 parent_no（18/18），
                        // 零合法反例，故硬拦不误伤存量。
                        assertParentNoPresent(cidStr, recursive, kept, missingParent);
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "[costing-tree-render] expand comp=%s failed: %s", cidStr, e.getMessage());
                    failedComponents.put(cidStr, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                rowsByCompThenMaterial.put(cidStr, byKey);
            }
        } finally {
            BomTreeVarsContext.clear();
        }
        if (!failedComponents.isEmpty()) {
            // 🔒 不能把「组件 expand 抛异常」悄悄降级成「该组件 0 行」——那会让 upgrade() 误报
            // SUCCESS（真实案例：ContextNotActiveException 272 次全被吞，核价卡片 17 个 tab 清零，
            // job 却全部标记 SUCCESS）。整体抛出，交由调用方（S5 的 upgrade()）作为失败处理，
            // 走既有 FAILED/重试通道，而不是带着残缺数据静默"成功"。
            throw new BusinessException(500, "核价树渲染失败：" + failedComponents.size() + " 个组件 expand 抛异常（"
                + String.join("; ", failedComponents.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toList())
                + "），为避免残缺数据冒充成功，本次渲染整体失败");
        }

        // ⑤ 逐卡逐组件装 baseRows
        for (Map.Entry<String, List<UUID>> e : rootToLineItemIds.entrySet()) {
            String root = e.getKey();
            LinkedHashSet<String> cardMaterials = g.cardMaterialNo.getOrDefault(root, new LinkedHashSet<>());
            List<CostingTreeNode> treeRows = g.treeRowsByRoot.getOrDefault(root, List.of());
            for (UUID liId : e.getValue()) {
                Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, List<ExpandDriverResponse.Row>>> ce : rowsByCompThenMaterial.entrySet()) {
                    String cidStr = ce.getKey();
                    Map<String, List<ExpandDriverResponse.Row>> byKey = ce.getValue();
                    ArrayNode baseRows = MAPPER.createArrayNode();
                    if (treeTabCompIds.contains(cidStr)) {
                        // 树页签：以卡片的 spine 节点为行主轴；按 (节点父件, 节点料号) 边键精确取该边的业务行
                        // → 同一子件挂多父时,每个节点只挂到自己那条边(别父件的边无对应节点 → 丢弃),缺数据补空行。
                        for (CostingTreeNode node : treeRows) {
                            List<ExpandDriverResponse.Row> bizRows = byKey.get(edgeKey(node.parentNo, node.materialNo));
                            if (bizRows != null && !bizRows.isEmpty()) {
                                for (ExpandDriverResponse.Row br : bizRows) {
                                    baseRows.add(treeRowNode(node, br));
                                }
                            } else {
                                baseRows.add(treeRowNode(node, null));
                            }
                        }
                    } else {
                        // 普通页签：卡片料号集合命中的行平铺（按 material_no,不变）
                        for (String mat : cardMaterials) {
                            List<ExpandDriverResponse.Row> bizRows = byKey.get(mat);
                            if (bizRows != null) {
                                for (ExpandDriverResponse.Row br : bizRows) {
                                    baseRows.add(flatRowNode(br));
                                }
                            }
                        }
                    }
                    baseRowsByComp.put(cidStr, baseRows);
                }
                out.put(liId, baseRowsByComp);
            }
        }
        return out;
    }

    /**
     * repair-0814 D-3（原 {@code BL-0172}）：树页签 {@code $view} 必须输出 {@code parent_no} 列。
     *
     * <p><b>改动前是一行 {@code LOG.warnf}</b>：渲染照常返回 200、该页签渲染成<b>满屏空行</b>，
     * 用户侧零提示，只能靠翻日志才知道是配置漏了列。现改为显式失败——与本类
     * {@code render()} 里 {@code failedComponents} 块「不能带着残缺数据静默"成功"」的既定口径统一
     * （该块注释记载的真实事故：272 次异常全被吞、17 个 tab 清零、job 却全报 SUCCESS）。
     *
     * <p><b>触发条件不得放宽</b>：{@code recursive} 且 {@code kept > 0} 且 <b>全部</b>行都没有父件列。
     * <ul>
     *   <li>{@code kept == 0}（一行都没留下）→ 不判，那是"无数据"不是"缺列"；</li>
     *   <li>{@code missingParent < kept}（只有部分行缺）→ 不判，那是<b>数据</b>问题不是<b>配置</b>问题；</li>
     *   <li>{@code !recursive}（普通页签按 material_no 分桶）→ 与 parent_no 无关，不判。</li>
     * </ul>
     *
     * <p><b>硬拦而非告警的依据</b>（2026-08-14 全库扫描 {@code cpq_db_0724}）：18 个
     * {@code bom_recursive_expand=true} 的组件，其 {@code component_sql_view.sql_template}
     * <b>全部</b>含 {@code parent_no}（18/18），零合法反例，故硬拦不误伤存量。
     *
     * <p>抽成独立方法是为了让这条判据可被单测直接覆盖（{@code BomTreeParentNoGuardTest}）——
     * 原先内联在 {@code render()} 的深层循环里，要测它得搭一整套 driver/$view 夹具。
     *
     * @param componentId   组件 id（仅用于错误文案）
     * @param recursive     该页签是否树页签（{@code bom_recursive_expand}）
     * @param kept          有效行数（有 {@code material_no} 的行）
     * @param missingParent 其中缺 {@code parent_no} 的行数
     */
    static void assertParentNoPresent(String componentId, boolean recursive, int kept, int missingParent) {
        if (!recursive || kept <= 0 || missingParent != kept) return;
        throw new BusinessException(400, "树页签组件 " + componentId + " 的 $view 未输出 parent_no 列（"
                + kept + " 行全无父件列）：树页签按 (parent_no, material_no) 边键匹配，"
                + "缺该列会退化为只命中根层空父 → 该页签业务数据全部落空（渲染成满屏空行）。"
                + "请让树页签 $view 同时输出 parent_no 与 material_no 两列。");
    }

    /** 树页签边键分隔符（U+0001，料号里不会出现，避免拼接歧义）。 */
    private static final String EDGE_SEP = "\u0001";

    /**
     * 树页签边键：{@code (父件料号, 子件料号)}。根节点父件为 {@code null}，用空串占位。
     */
    static String edgeKey(String parentNo, String materialNo) {
        return (parentNo == null ? "" : parentNo) + EDGE_SEP + (materialNo == null ? "" : materialNo);
    }

    /**
     * 递归 SQL 支持的占位符（按出现顺序绑定，见 {@link #queryRecursive}）。task-0721 B4 追加
     * {@code pq}（{@link com.cpq.datasource.sqlview.QuotePendingRewriter#PENDING_PARAM}，pending
     * 感知表替换注入的命名参数）——与 {@code production_part_nos}/{@code __vfPart}/{@code __vfVer}
     * 一样按<b>出现顺序</b>绑定，而非按类型分组批量绑定（3+1 种占位符可能交替出现）。
     */
    private static final java.util.regex.Pattern TREE_PARAM =
            java.util.regex.Pattern.compile(":(production_part_nos|__vfPart|__vfVer|pq)\\b");

    /**
     * 递归 SQL 直接 JDBC 执行。契约里的绑定变量是 {@code :production_part_nos}（text[]，递归 CTE 常见
     * 写法会在 SQL 里多次引用同一变量，如 base case + recursive case 各引用一次），task-0713 B3 起还
     * 支持 {@code :versionFilter(...)} 宏展开后的 {@code :__vfPart / :__vfVer}（同样可能多次出现，
     * 例如本表版本列的展示子查询 + 主 JOIN 谓词各一次）。S3 修复（2026-07）确立的「按出现次数逐个绑定」
     * 范式在此扩展为<b>按出现顺序</b>逐个绑定（3 种占位符可能交替出现，不能像单占位符那样按类型分组
     * 批量绑定，否则位置错位）。
     *
     * @param treeOverrides 「主树」组件（{@code bom_recursive_expand=true}）在本核价单的 override
     *                      （parentPartNo → viewVersion）；null/空 = 零覆盖，宏展开后恒退化为
     *                      is_current（与未接入版本切换前逐位等价）。
     *
     * <p><b>task-0721 B4（与树任务协同，已接线）</b>：本方法走裸 JDBC，不经过
     * {@link com.cpq.datasource.sqlview.SqlViewExecutor}，故报价升版逻辑 B3 的
     * {@link com.cpq.datasource.sqlview.QuotePendingRewriter} pending 感知表替换<b>不会自动覆盖</b>
     * 本递归 CTE——本方法现已在"报价单 + 非冻结态"上下文下（与
     * {@code SqlViewExecutor.applyPendingRewrite} 完全同款的门槛判定：
     * {@code owner.quotationId != null && !owner.isQuotationFrozen()}）对 {@code expanded} 整体
     * 跑一遍 {@code QuotePendingRewriter.rewrite}，让递归 CTE 的 base case + recursive case 里
     * 对白名单表（{@code material_bom_item} 等）的<b>每一处</b>引用都换成 pending 感知子查询——
     * 否则一个全新产品（官方 BOM 全无、只有本单 pending）在物化期会因递归闭包查不到任何 pending
     * 行而整棵树 0 节点渲染，AC-2「本单可见」失败。改写产生的 {@code :pq} 占位符纳入
     * {@link #TREE_PARAM} 按出现顺序绑定为标量 uuid（而非数组）。
     * <p>递归 CTE 本身（spine：root_no/material_no/bom_version/parent_no/node_path）<b>不需要</b>
     * 额外注入 {@code __v6_id} 锚点——它只是结构定位（哪个节点挂哪个父），不是可回填的业务行；
     * 真正需要回填锚点的是各树页签"业务行"侧（{@link #treeRowNode} 透传的
     * {@code ExpandDriverResponse.Row.driverRow}），那条路径走 {@code $view} → 已经随 B3 的
     * {@code SqlViewExecutor.executeAllRows} 自动带出 {@code __v6_id}、原样落入
     * {@code snapshot_rows}，不需要在本方法内重复处理。
     * <p>核价侧（usage=COSTING）/无 quotationId 上下文/已提交冻结报价单：门槛判定为 false，
     * {@code expanded} 原样执行，零回归（AC-17/AC-10）。
     */
    private List<CostingTreeNode> queryRecursive(String sqlTemplate, List<String> seed,
                                                  Map<String, String> treeOverrides) {
        String expanded = VersionFilterMacro.containsMacro(sqlTemplate)
                ? VersionFilterMacro.expandForExecution(sqlTemplate) : sqlTemplate;

        List<String> vfPart = new ArrayList<>();
        List<String> vfVer = new ArrayList<>();
        if (treeOverrides != null) {
            for (Map.Entry<String, String> e : treeOverrides.entrySet()) {
                vfPart.add(e.getKey());
                vfVer.add(e.getValue());
            }
        }

        List<CostingTreeNode> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            java.util.UUID pendingQuotationId = resolvePendingOwner();
            String withPending = expanded;
            if (pendingQuotationId != null) {
                try {
                    // task-0725 T3 收尾修复：injectAnchor=false —— 递归 CTE spine 不需要 __v6_id 锚点
                    // （见 QuotePendingRewriter#rewrite(sql,conn,injectAnchor) javadoc），且该锚点探测的
                    // fallback 启发式在此类 SQL 上会误伤 SELECT 列表内的相关子查询，产生
                    // "subquery must return only one column"（2026-07-25 端到端实测复现）。
                    com.cpq.datasource.sqlview.QuotePendingRewriter.Result rw =
                        com.cpq.datasource.sqlview.QuotePendingRewriter.rewrite(expanded, conn, false);
                    withPending = rw.sql;
                } catch (Exception ex) {
                    LOG.warnf("[costing-tree] pending 感知改写失败，递归 SQL 原样执行（本单 pending 行可能不可见）: %s",
                        ex.getMessage());
                }
            }

            // task-0725 根因 2：定位前先 mask 屏蔽注释/字面量，避免树配置注释里写到
            // :production_part_nos / :pq / :__vfPart / :__vfVer 时被误当占位符替换成 ?（pgjdbc
            // 会忽略注释内的 ?，导致 order.size() 与 pgjdbc 实际占位符数错位）。⚠️ 屏蔽/匹配必须继续
            // 作用在 withPending（pending 改写之后），不是 expanded —— T2 生效后 pending 改写会在
            // withPending 里生成大量合法的 :pq token（QuotePendingRewriter:229/235/236），挪到
            // expanded 上匹配会导致这些合法 :pq 绑不上。mask() 保留原文长度与换行，masked 文本上的
            // start()/end() 可直接映射回 withPending 的同一偏移量，写入 rewritten 的仍是 withPending
            // 原文内容。
            String maskedForTreeParams = com.cpq.datasource.sqlview.SqlTextMask.mask(withPending);
            java.util.regex.Matcher m = TREE_PARAM.matcher(maskedForTreeParams);
            StringBuilder rewritten = new StringBuilder();
            List<String> order = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                rewritten.append(withPending, lastEnd, m.start()).append('?');
                order.add(m.group(1));
                lastEnd = m.end();
            }
            rewritten.append(withPending, lastEnd, withPending.length());

            String sql = "SELECT root_no, material_no, bom_version, parent_no, node_path FROM (" + rewritten + ") q";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                java.sql.Array seedArr = conn.createArrayOf("text", seed.toArray());
                java.sql.Array partArr = conn.createArrayOf("text", vfPart.toArray());
                java.sql.Array verArr = conn.createArrayOf("text", vfVer.toArray());
                for (int i = 0; i < order.size(); i++) {
                    String name = order.get(i);
                    if ("pq".equals(name)) {
                        ps.setObject(i + 1, pendingQuotationId);
                        continue;
                    }
                    java.sql.Array arr = "production_part_nos".equals(name) ? seedArr
                            : "__vfPart".equals(name) ? partArr : verArr;
                    ps.setArray(i + 1, arr);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new CostingTreeNode(
                                rs.getString("root_no"),
                                rs.getString("material_no"),
                                rs.getString("bom_version"),
                                rs.getString("parent_no"),
                                rs.getString("node_path")));
                    }
                }
            }
        } catch (Exception e) {
            throw new BusinessException(500, "核价树递归 SQL 执行失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * task-0721 B4：与 {@code SqlViewExecutor.applyPendingRewrite} 完全同款的门槛判定——
     * 报价单上下文 + 非冻结态 → 返回本单 quotationId（改写生效）；核价侧/无上下文/已冻结 → null
     * （不改写，零回归）。
     *
     * <p>task-0725 T2：改读 {@link com.cpq.datasource.sqlview.QuotePendingScope#pendingOwner()}
     * （原读 {@code SqlViewRuntimeContext}——而 {@code ConfigureSnapshotService:350} 调
     * {@code render()} 那一刻从未有任何 {@code setNested} 发生过，driver 的 {@code setNested} 在更
     * 内层的 {@code ComponentDriverService.expandUncached→expand}，故原实现恒返回 null，这是报价树侧
     * 独立于「组件页签」的第二个断点）。{@code QuotePendingScope.pendingOwner()} 已内建冻结判定
     * （非 null ⟹ 非冻结），门槛判定与 {@code SqlViewExecutor.applyPendingRewrite} 完全同款，
     * 消费方（本方法）不得再判 frozen。
     */
    private java.util.UUID resolvePendingOwner() {
        return com.cpq.datasource.sqlview.QuotePendingScope.pendingOwner();
    }

    // ─── baseRow 装配纯函数（结构对齐 CardSnapshotService#rowToNode / #spineRowNode） ───

    static ObjectNode rowNodeFrom(ExpandDriverResponse.Row row) {
        ObjectNode n = MAPPER.createObjectNode();
        n.set("driverRow",
                (row != null && row.driverRow != null) ? MAPPER.valueToTree(row.driverRow) : MAPPER.createObjectNode());
        n.set("basicDataValues",
                (row != null && row.basicDataValues != null) ? MAPPER.valueToTree(row.basicDataValues) : MAPPER.createObjectNode());
        return n;
    }

    /** 普通页签一行（对齐 {@code CardSnapshotService#rowToNode}，无系统列）。 */
    static ObjectNode flatRowNode(ExpandDriverResponse.Row bizRow) {
        return rowNodeFrom(bizRow);
    }

    /**
     * 树页签一行（对齐 {@code CardSnapshotService#spineRowNode}）：业务行 + 系统列
     * {@code __nodeId/__parentId/__lvl/__hfPartNo/__parentNo/__bomVersion}。
     *
     * <p>{@code bizRowOrNull=null} 时仍输出系统列（业务行缺失 = 空 basicDataValues）；driverRow 补
     * {@code material_no = node.materialNo} 作锚点（S2 修复，2026-07；对齐 §4.2「必含 material_no」——
     * 有业务行时业务行自带 material_no 不用补，空节点时前端/下游按 material_no 取数不能没锚点）。
     * 注：新递归 SQL 契约（root_no/material_no/bom_version/parent_no/node_path）无 is_cycle 列，
     * 故本行不含旧 {@code spineRowNode} 的 {@code __isCycle}（详见交接说明）。
     */
    static ObjectNode treeRowNode(CostingTreeNode node, ExpandDriverResponse.Row bizRowOrNull) {
        ObjectNode rowNode = rowNodeFrom(bizRowOrNull);
        if (bizRowOrNull == null) {
            ((ObjectNode) rowNode.get("driverRow")).put("material_no", node.materialNo);
        }
        rowNode.put("__nodeId", node.nodeId == null ? "" : node.nodeId);
        if (node.parentId == null) {
            rowNode.putNull("__parentId");
        } else {
            rowNode.put("__parentId", node.parentId);
        }
        rowNode.put("__lvl", node.lvl);
        if (node.materialNo == null) {
            rowNode.putNull("__hfPartNo");
        } else {
            rowNode.put("__hfPartNo", node.materialNo);
        }
        if (node.parentNo == null) {
            rowNode.putNull("__parentNo");
        } else {
            rowNode.put("__parentNo", node.parentNo);
        }
        if (node.bomVersion == null) {
            rowNode.putNull("__bomVersion");
        } else {
            rowNode.put("__bomVersion", node.bomVersion);
        }
        return rowNode;
    }
}
