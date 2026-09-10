package com.cpq.component.service;

import com.cpq.component.entity.CostingBomTreeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 核价/报价树递归 SQL 配置的 CRUD + 设为生效编排。
 *
 * <p>task-0721 B2：新增 {@code usage} 维度。同一 usage 同一时刻最多一条 {@code isActive=true}
 * （DB 部分唯一索引 {@code uq_bom_tree_config_active_per_usage} 按 usage 分别约束，
 * {@link #setActive(UUID)} 单事务内只下线<b>同 usage</b> 的旧生效配置，<b>不影响另一侧</b>）。
 *
 * <p>🔄 <b>task-260909 B-2：值域由 2 值扩到 4 值</b>（{@code api.md §1.1}）——
 * {@code QUOTE}(报价) / {@code COST_BASIC}(基础核价) / {@code COST_DETAIL}(详细核价)，
 * 外加 {@code COSTING} 作为 {@code COST_BASIC} 的<b>只读兼容别名</b>。
 * <ul>
 *   <li><b>零 DDL</b>：{@code usage} 列已是 {@code varchar(16)} 且<b>无 CHECK 约束</b>，
 *       唯一索引按 usage 分组 ⇒ 扩值天然生效，<b>不新增任何迁移文件</b>（2026-09-09 实测
 *       {@code information_schema} + {@code pg_constraint} 确认）。</li>
 *   <li><b>为什么写入要拒绝 {@code COSTING}</b>：三套并存后再留一个"等价别名"可写，等于允许
 *       同一份配置有两种存法 —— 一半记录 {@code usage='COSTING'}、一半 {@code 'COST_BASIC'}，
 *       而唯一索引按<b>字面量</b>分组 ⇒ 两条同时 {@code isActive} 也不冲突，
 *       渲染期取哪条全看归一化写在谁那里。故写入侧一律 400。</li>
 *   <li><b>为什么 {@code POST} 未传 usage 不再兜底</b>：兜底的失败形态是"用户选了详细核价、
 *       系统写成基础核价"，且全程不报错（{@code api.md §1.1}）。</li>
 * </ul>
 * ⚠️ 存量 {@code usage='QUOTE'} 记录<b>一个字节不动</b>（AC-22 零回归门禁）：本次没有任何
 * 迁移、也没有任何代码路径会去规范化它。
 */
@ApplicationScoped
public class CostingBomTreeConfigService {

    private static final Logger LOG = Logger.getLogger(CostingBomTreeConfigService.class);

    /** 已停用的旧值 —— 只读兼容别名，等价 {@link #CANONICAL_COSTING_USAGE}。 */
    public static final String LEGACY_USAGE_ALIAS = "COSTING";
    /** {@link #LEGACY_USAGE_ALIAS} 的归一目标。 */
    public static final String CANONICAL_COSTING_USAGE = "COST_BASIC";

    /** 可<b>读</b>值域（含只读兼容别名 {@code COSTING}）。 */
    private static final java.util.Set<String> READABLE_USAGES =
            java.util.Set.of("QUOTE", "COST_BASIC", "COST_DETAIL", LEGACY_USAGE_ALIAS);
    /** 可<b>写</b>值域（api.md §1.1：{@code COSTING} 已停用）。 */
    public static final java.util.List<String> WRITABLE_USAGES =
            java.util.List.of("QUOTE", "COST_BASIC", "COST_DETAIL");

    /** usage → 中文数据集名（B-4 报错文案 / 日志用）。 */
    public static String usageDisplayName(String usage) {
        if (usage == null) return "未知";
        return switch (usage.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "QUOTE" -> "报价";
            case "COST_BASIC", LEGACY_USAGE_ALIAS -> "基础核价";
            case "COST_DETAIL" -> "详细核价";
            default -> usage;
        };
    }

    @Inject
    CostingTreeSqlValidator validator;

    @Inject
    EntityManager em;

    @Inject
    com.cpq.template.service.PublishedTemplateReader publishedTemplateReader;

    public List<CostingBomTreeConfig> list() {
        return CostingBomTreeConfig.listAll();
    }

    /**
     * task-0721 B2：按 usage 过滤；usage 为 null/blank → 返回全部（向后兼容，api.md §1.1）。
     *
     * <p>task-260909 B-2：{@code ?usage=COSTING} 按 {@code COST_BASIC} 处理并返回其记录
     * （只读兼容别名，存量客户端不会碎）。
     */
    public List<CostingBomTreeConfig> list(String usage) {
        if (usage == null || usage.isBlank()) return list();
        return CostingBomTreeConfig.list("usage", normalizeUsage(usage));
    }

    /**
     * <b>读路径</b>归一（task-260909 B-2）：{@code COSTING → COST_BASIC}；非四值之一 → 400。
     *
     * <p>🔑 公开静态是刻意的：{@code BomTreeRenderService} 的骨架取用点必须与本类走
     * <b>同一份归一规则</b>（`backtask §0-2`：确认无第二处硬编码 {@code "COSTING"}）——
     * 各写一份的失败形态是「界面上存进 COST_BASIC、渲染期照 COSTING 找、找不到就报未配置」。
     */
    public static String normalizeUsage(String usage) {
        if (usage == null || usage.isBlank()) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "usage 必填，合法值：" + String.join(" / ", WRITABLE_USAGES));
        }
        String u = usage.trim().toUpperCase(java.util.Locale.ROOT);
        if (!READABLE_USAGES.contains(u)) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "非法 usage: " + usage + "，合法值：" + String.join(" / ", WRITABLE_USAGES));
        }
        return LEGACY_USAGE_ALIAS.equals(u) ? CANONICAL_COSTING_USAGE : u;
    }

    /**
     * <b>写路径</b>归一：在 {@link #normalizeUsage} 之上额外拒绝只读兼容别名 {@code COSTING}
     * （api.md §1.1）。
     */
    private static String normalizeUsageForWrite(String usage) {
        if (usage != null && LEGACY_USAGE_ALIAS.equalsIgnoreCase(usage.trim())) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "COSTING 已停用，请选择 COST_BASIC 或 COST_DETAIL");
        }
        return normalizeUsage(usage);
    }

    /**
     * 2 参便利重载 —— <b>不是端点路径</b>（端点侧 {@code POST} 未传 usage 必须 400，
     * 见 {@link #create(String, String, String)}）。默认落 {@link #CANONICAL_COSTING_USAGE}，
     * 原默认值 {@code COSTING} 自 task-260909 起不可写。
     */
    @Transactional
    public CostingBomTreeConfig create(String name, String sqlTemplate) {
        return create(name, sqlTemplate, CANONICAL_COSTING_USAGE);
    }

    /** task-0721 B2 / task-260909 B-2：usage 必填且必须是三个可写值之一（api.md §1.1）。 */
    @Transactional
    public CostingBomTreeConfig create(String name, String sqlTemplate, String usage) {
        // 🔑 usage 校验放在 SQL 校验**之前**：SQL 校验要连库 dry-run，非法 usage 没必要付这笔开销，
        //    且先报"usage 非法"比先报"SQL 无法执行"更贴近用户实际做错的那件事。
        String normalized = normalizeUsageForWrite(usage);
        CostingTreeSqlValidator.Result r = validator.validate(sqlTemplate);
        if (!r.ok) {
            throw new RuntimeException("递归 SQL 校验失败: " + r.message);
        }
        CostingBomTreeConfig e = new CostingBomTreeConfig();
        e.name = name;
        e.sqlTemplate = sqlTemplate;
        e.isActive = false;
        e.usage = normalized;
        e.persist();
        return e;
    }

    @Transactional
    public CostingBomTreeConfig update(UUID id, String name, String sqlTemplate) {
        return update(id, name, sqlTemplate, null);
    }

    /** task-0721 B2：usage 非 null 时覆盖（null=不变,零破坏）。 */
    @Transactional
    public CostingBomTreeConfig update(UUID id, String name, String sqlTemplate, String usage) {
        CostingTreeSqlValidator.Result r = validator.validate(sqlTemplate);
        if (!r.ok) {
            throw new RuntimeException("递归 SQL 校验失败: " + r.message);
        }
        CostingBomTreeConfig e = CostingBomTreeConfig.findById(id);
        if (e == null) {
            throw new RuntimeException("配置不存在: " + id);
        }
        e.name = name;
        e.sqlTemplate = sqlTemplate;
        if (usage != null && !usage.isBlank()) {
            e.usage = normalizeUsageForWrite(usage);
        }
        // §10 失效钩子（Task 3.1 集成阶段接入，见 invalidateTreeTabCostingCardValues 注释）：
        // update() 改的是"当前配置的 SQL 内容"——只有它已经是 isActive=true 时才会被下次渲染实际读取。
        // 非生效配置改动不影响任何已渲染快照，故仅当 e.isActive 时才失效。
        if (e.isActive) {
            invalidateRenderedCardValues(e.usage);
        }
        return e;
    }

    /**
     * 设为生效：单事务先把<b>同 usage</b> 除目标外的当前 active 置 false（避开部分唯一索引），
     * 再置目标 true。<b>不影响另一 usage</b>（api.md §2.3 语义变更：全局唯一 active → 每 usage 至多一条）。
     *
     * <p>用<b>受管实体</b>写(非 bulk UPDATE)：
     * <ul>
     *   <li>幂等正确：对<b>已生效</b>配置再调一次时 {@code current==target} → 跳过置 false，
     *       {@code target.isActive=true} 保持 true(no-op),该行仍生效;
     *   <li>不产生陈旧读:bulk UPDATE 不会刷新持久化上下文里已加载的实体,后续同上下文
     *       {@code findById}/{@code findActive} 会读到旧一级缓存值(true 被误读为 false);受管实体写则
     *       读回一致。
     * </ul>
     * <p>部分唯一索引 {@code uq_bom_tree_config_active_per_usage}(同一 usage 同一时刻最多一条 true):
     * 先把同 usage 旧生效行置 false 并 {@code em.flush()} 落库,再置目标 true,避免瞬时两条 true 违反索引。
     */
    @Transactional
    public void setActive(UUID id) {
        CostingBomTreeConfig target = CostingBomTreeConfig.findById(id);
        if (target == null) {
            throw new RuntimeException("配置不存在: " + id);
        }
        CostingBomTreeConfig current = CostingBomTreeConfig.findActive(target.usage);
        if (current != null && !current.id.equals(target.id)) {
            current.isActive = false;
            em.flush();   // 先让旧生效行落库为 false,再置目标 true(部分唯一索引)
        }
        target.isActive = true;
        invalidateRenderedCardValues(target.usage);
    }

    /**
     * task-0721 B2：按 usage 分派失效钩子。
     *
     * <p><b>COSTING</b>：委派原 {@link #invalidateTreeTabCostingCardValues()}（核价侧逐位不变，
     * 核价卡片值每次都从基础数据/递归 SQL 现算，config 变更应立即（懒算式）反映）。
     *
     * <p><b>QUOTE</b>：<b>刻意 no-op</b>。报价侧树在物化阶段产生并冻结（需求说明 §4.3 规则五：
     * 「已提交的报价单再次打开，树结构不随基础数据 BOM 变动而漂移」）——若对已冻结的
     * {@code snapshot_rows}/{@code quote_card_values} 做同款失效，等于让历史报价单的树结构随
     * 配置变更漂移，直接违反冻结不变量。配置变更只对<b>未来</b>物化（新建报价单 / 加产品 /
     * saveDraft 首次重建）生效，不回溯影响已冻结数据。
     */
    private void invalidateRenderedCardValues(String usage) {
        if ("QUOTE".equals(usage)) {
            LOG.infof("[costing-bom-tree-config] QUOTE usage 配置变更：报价侧树快照已冻结，" +
                    "不回溯失效（规则五），仅对未来物化生效");
            return;
        }
        invalidateTreeTabCostingCardValues();
    }

    /**
     * §10 失效钩子（Task 3.1 集成阶段接入）：递归 SQL 配置切换生效 / 改动生效配置内容后，
     * 只有<b>核价模板挂了树页签组件（{@code bom_recursive_expand=true}）</b>的存量报价单，其
     * {@code costingCardValues} 才依赖本配置渲染（见 {@link com.cpq.quotation.service.BomTreeRenderService}
     * 与 {@code CardSnapshotService#templateHasTreeTab}）；不含树页签的模板/报价单完全不读本配置，
     * 若做不加区分的全局 {@code UPDATE quotation_line_item SET costing_card_values = NULL} 会误伤它们
     * （下次打开被迫走一次不必要的懒算）。故用 {@code EXISTS} 精确收窄到受影响的报价单行。
     *
     * <p>只置 NULL，不同步重算——沿用既有 P3 懒算纪律（{@code CardSnapshotService#ensureCardValues} 的
     * {@code IS NULL} 谓词下次打开该报价单/调用 ensureCardValues 时自动补算，单飞锁防并发重复算），
     * 不在本次配置切换事务里做重量级同步批量渲染。
     *
     * <p><b>task-0806 B19</b>：原 EXISTS 子查询直读 {@code component}/{@code template_component} 活表
     * 判定"该核价模板是否含 {@code bom_recursive_expand=true} 组件"——改经
     * {@link com.cpq.template.service.PublishedTemplateReader#hasRecursiveExpand} 取冻结快照判定：
     * 先取当前在用的 distinct {@code costing_card_template_id}（数量=在用 COSTING 模板数，§1.6 实测
     * 仅 2 个，与报价单/行数无关，SQL 条数不随 N 增长），逐个查 {@code hasRecursiveExpand}
     * （O(模板数)，非 O(报价单数)，不违反 N+1 铁律），再用命中的模板 id 集合一次
     * {@code UPDATE ... WHERE EXISTS (... costing_card_template_id = ANY(:tids))}。单个模板判定失败
     * （理论不达）只记警告并跳过该模板，不让整批失效任务因一个坏模板整体失败——本方法是配置切换的
     * 维护性批处理，稳妥优先于严格（与 FR-6"禁止回落活表"约束的是"单次渲染 miss"场景不同）。
     */
    @Transactional
    void invalidateTreeTabCostingCardValues() {
        @SuppressWarnings("unchecked")
        List<Object> tplRows = em.createNativeQuery(
                "SELECT DISTINCT costing_card_template_id FROM quotation WHERE costing_card_template_id IS NOT NULL")
                .getResultList();
        List<UUID> matched = new ArrayList<>();
        for (Object o : tplRows) {
            if (o == null) continue;
            UUID tid = (o instanceof UUID u) ? u : UUID.fromString(o.toString());
            try {
                if (publishedTemplateReader.hasRecursiveExpand(tid)) matched.add(tid);
            } catch (Exception e) {
                LOG.warnf("[costing-bom-tree-config] hasRecursiveExpand 判定失败 templateId=%s: %s（跳过,不纳入失效范围）",
                        tid, e.getMessage());
            }
        }
        int n = 0;
        if (!matched.isEmpty()) {
            n = em.createNativeQuery(
                    "UPDATE quotation_line_item li SET costing_card_values = NULL, costing_excel_values = NULL " +
                    "WHERE li.costing_card_values IS NOT NULL AND EXISTS ( " +
                    "  SELECT 1 FROM quotation q WHERE q.id = li.quotation_id " +
                    "  AND q.costing_card_template_id = ANY(:tids) )")
                .setParameter("tids", matched.toArray(new UUID[0]))
                .executeUpdate();
        }
        LOG.infof("[costing-bom-tree-config] 递归 SQL 配置变更，失效 %d 行含树页签模板的存量核价卡片值(懒算重算)", n);
    }

    @Transactional
    public void delete(UUID id) {
        CostingBomTreeConfig.deleteById(id);
    }
}
