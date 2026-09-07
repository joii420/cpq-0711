package com.cpq.component.service;

import com.cpq.component.entity.ComponentSqlView;
import com.cpq.semanticgraph.entity.SemanticTabView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-260904 B-4/B-18 —— 「这个组件是不是 BOM 树页签 / 受限页签」的<b>全工程唯一判据实现</b>
 * （需求文档 §1.35「核心机制：双判据并存」）。
 *
 * <pre>
 * 判定一个组件是否为「BOM 树页签」：
 *   ① component_sql_view.builder_version IS NOT NULL（= 取数配置器产出的新模型组件）
 *        → 从 builder_config 取 (tabType, variantKey, dialect) 三段坐标，
 *          查 semantic_tab_view 得 tab_type，映射为 semantic，判 semantic == 'TREE'
 *   ② 否则（存量组件）
 *        → 回退读 component.tab_type == 'BOM'
 * </pre>
 *
 * <p><b>三段坐标缺一不可</b>：{@code semantic_tab_view} 的唯一约束实测为
 * {@code UNIQUE (tab_type, variant_key, dialect)}；只用前两段反查会命中 3 行（QUOTE /
 * COST_BASIC / COST_DETAIL 各一），{@code findFirst()} 会静默取到错误方言的声明。
 *
 * <p><b>分支①优先</b>：同时存在 {@code builder_config} 与 {@code tab_type='BOM'} 时以
 * {@code semantic} 为准，不被历史 {@code tab_type} 值污染（AC-27③）。
 *
 * <p>🚨 <b>不要在别处散落 {@code "BOM".equals(...)}</b>——本类是收口点，全工程 9 个消费点一律
 * 调用本类（AC-22）。{@code "BOM"} 这个字面量在业务代码里只允许出现在本文件内。
 *
 * <p><b>N+1 纪律</b>：本类的批量入口（{@link #builderSemantics} / {@link #isTreeTabBatch}）
 * 每次调用固定 ≤2 条 SQL，与入参组件数无关；单点入口 {@link #isTreeTab} 是批量入口的
 * 1 元素特化，<b>禁止放进循环</b>——调用方拿到的若是组件列表，一律走批量入口。
 * 现网 {@code builder_version} 为 0 行 ⇒ 第 2 条 SQL（{@code semantic_tab_view}）根本不会触发。
 */
@ApplicationScoped
public class TabSemanticResolver {

    private static final Logger LOG = Logger.getLogger(TabSemanticResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── semantic 三态（api.md §1.2） ────────────────────────────────────────
    /** 该数据源是 BOM 树。 */
    public static final String SEMANTIC_TREE = "TREE";
    /** 该数据源是材质元素（接元素价格策略 + 固定元素列）。 */
    public static final String SEMANTIC_MATERIAL_ELEMENT = "MATERIAL_ELEMENT";
    /** 普通平铺数据源（无额外语义）。map 里用空串表达「已绑定但无语义」，与「未绑定」区分开。 */
    public static final String SEMANTIC_PLAIN = "";

    // ── 值域：V417 之后两侧同值，但**判据仍是两条**（见下方警示） ─────────────
    /*
     * 🩹 值域简史（2026-09-05 收敛，务必读完再改）：
     *
     *   task-260819 的 D-39 原意是「键值 'BOM'、显示名 'BOM 树'，两者故意不同」，但其 V413 种子
     *   把**显示名**写进了 semantic_tab_view.tab_type 这个**键值列**，三方言全错，只有 BOM 这一类
     *   对不上（其余五类两边逐字一致）。
     *
     *   **V417（2026-09-05 02:04 应用到共享库，master 648c9485）已把键值改回 'BOM'** ⇒ 两侧同值，
     *   故此处由两个常量收敛为一个 TAB_TYPE_TREE。
     *
     *   ⚠️ 该缺陷的杀伤形态值得记一笔：库里的值一变，semanticOfGraphTabType 落进 default 分支
     *   静默返回 PLAIN，**BOM 树页签被判成普通页签**——bom_recursive_expand 恒 false、tree_ref
     *   公式闸门 400，全程不报错。为此本类新增了 warnOnUnknownGraphTabType()：**任何不在
     *   已知值域内的图侧 tab_type 一律 WARN**，让下一次值域漂移「响」而不是静默降级。
     *
     * 🚨 **合并的只是「值」，不是「判据」** —— 分支① 读 component_sql_view.builder_config.tabType，
     *    分支② 读 component.tab_type，两个数据来源、两条语义，**不许顺手把两个分支也合了**。
     *    保留两个命名（TAB_TYPE_TREE 的两处引用点各自注明属于哪条分支）正是为了这一点。
     */
    /** 树页签的 tab_type 取值。V417 后 {@code component.tab_type} 与 {@code semantic_tab_view.tab_type} 同为此值。 */
    private static final String TAB_TYPE_TREE = "BOM";

    /**
     * V417 之前 {@code semantic_tab_view.tab_type} 上的树取值（V413 笔误写入的显示名）。
     *
     * <p>保留一条**会自报家门的**兼容分支，而不是彻底删掉：共享 dev 库确实已 V417，但
     * {@code deploy/cpq-init.sql}（单文件建库脚本，不在 git、不随迁移自动重生成）与任何
     * V417 之前的库备份里仍可能是旧值。删掉 = 那类环境上「新建树组件静默变普通页签」原样重演；
     * 保留 = 仍判为树，**并打 WARN 提示该库的语义图种子过期**。命中即异常，不是常态。
     */
    private static final String TAB_TYPE_TREE_PRE_V417 = "BOM 树";

    /** {@code component.tab_type} 的受限页签取值（B-9 分支②判据，与改动前逐字一致）。 */
    private static final String LEGACY_TAB_TYPE_MATERIAL_ELEMENT = "材质元素";
    private static final String LEGACY_TAB_TYPE_OUTSOURCED = "外购件";

    /** 材质元素在两侧同为此值（V413 没写错这一类）。 */
    private static final String TAB_TYPE_MATERIAL_ELEMENT = "材质元素";

    /*
     * ✅ 已裁决（2026-09-05 主线转达 task-260819 的回执）：本兜底<b>保留</b>——它防的是
     * 「拿不到方言」，不是「拿错方言」，对方合并后仍然有效。
     * 对方分支的现状（合并后本注释下面描述的「现网」部分即失效，兜底本身不失效）：
     *   · 前端确实发 dialect（19 处）；FieldTreeBuilder 三处查找都带 dialect；
     *   · CompileDialect 已扩成 QUOTE / COST_BASIC / COST_DETAIL 三值，COSTING 作废且显式 400，
     *     不再静默回落 —— 即本方法里 "COSTING 一对二映射不唯一" 这个歧义届时自然消失。
     *
     * ── 以下为开发期（master 版本）的实测记录，保留作为兜底存在的理由 ──
     * builder_config.dialect
     * 在现网实际上<b>取不到值</b> —— 前端全工程零处发送该字段（实测 {@code cpq-frontend/src} 无
     * {@code dialect} 引用），且 {@code BuilderConfig.dialect} 的 javadoc 自述「不是 builder_config
     * JSONB 的持久化字段」。更要紧的是两侧值域并不重合：{@code CompileDialect} 只有
     * {@code QUOTE / COSTING} 两个取值，而 {@code semantic_tab_view.dialect} 是
     * {@code QUOTE / COST_BASIC / COST_DETAIL} —— {@code COSTING} 无法唯一映射到后两者之一。
     *
     * <p>本实现采取的处置（<b>可一处改掉</b>，见 {@link #normalizeDialect}）：
     * <ul>
     *   <li>能唯一确定方言 → 严格按三段坐标精确匹配（满足「三段缺一不可」）；</li>
     *   <li>方言缺失 / 为 {@code COSTING}（不可唯一映射）→ <b>不</b> {@code findFirst()} 静默取一行，
     *       而是取 {@code (tabType, variantKey)} 的<b>全部</b>候选行，只有当它们的 semantic
     *       <b>完全一致</b>时才采用该结果；不一致 → 判定为「解析不出」，回退分支②并打 WARN。</li>
     * </ul>
     * 之所以这样兜底而不是直接当 QUOTE：semantic 只由 {@code tab_type} 决定，而 {@code tab_type}
     * 本身就是坐标的一段 ⇒ 三个方言行的 semantic 必然相同，取哪一行都不会取错；一旦将来出现不一致，
     * 这个写法会拒绝判定并留下 WARN，而不是悄悄取错方言。
     */

    // =========================================================================
    // 分支②：存量回退判据（唯一实现）
    // =========================================================================

    /**
     * 分支②：存量组件按 {@code component.tab_type == 'BOM'} 判树。
     *
     * <p>只在两种场合直接调用：① 本类内部；② 调用点在结构上<b>确定不可能有</b>
     * {@code builder_config}（例如组件导入 —— bundle 不携带 {@code builder_config}/
     * {@code builder_version}，新建的 {@code component_sql_view} 两列恒为 NULL）。
     * 其余一律走 {@link #isTreeTab} / {@link #isTreeTabBatch}。
     */
    public static boolean isLegacyTreeTabType(String tabType) {
        return TAB_TYPE_TREE.equals(tabType);   // 分支②：读的是 component.tab_type
    }

    /** 分支②：受限页签（材质元素 / 外购件）—— B-9 的存量判据，与改动前逐字一致。 */
    public static boolean isLegacyRestrictedTabType(String tabType) {
        return LEGACY_TAB_TYPE_MATERIAL_ELEMENT.equals(tabType)
                || LEGACY_TAB_TYPE_OUTSOURCED.equals(tabType);
    }

    // =========================================================================
    // 双判据入口
    // =========================================================================

    /**
     * 单点判定：该组件是否为报价侧 BOM 树页签。
     *
     * @param componentId 组件 id；{@code null}（如新建流程尚未 persist）→ 结构上不可能有
     *                    {@code builder_config}，直接走分支②
     * @param tabType     该组件<b>当前生效</b>的 {@code component.tab_type}（活表或冻结快照均可）
     */
    public boolean isTreeTab(UUID componentId, String tabType) {
        if (componentId == null) return isLegacyTreeTabType(tabType);
        Map<UUID, String> sem = builderSemantics(List.of(componentId));
        return decideTree(sem.get(componentId), sem.containsKey(componentId), tabType);
    }

    /**
     * 批量判定（N+1 硬指标：固定 ≤2 条 SQL，与入参个数无关）。
     *
     * @param tabTypeByComponentId componentId → 该组件当前生效的 {@code tab_type}（值可为 null）
     * @return componentId → 是否树页签；入参里的每个 key 都会出现在结果里
     */
    public Map<UUID, Boolean> isTreeTabBatch(Map<UUID, String> tabTypeByComponentId) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        if (tabTypeByComponentId == null || tabTypeByComponentId.isEmpty()) return out;
        Map<UUID, String> sem = builderSemantics(tabTypeByComponentId.keySet());
        for (Map.Entry<UUID, String> e : tabTypeByComponentId.entrySet()) {
            out.put(e.getKey(), decideTree(sem.get(e.getKey()), sem.containsKey(e.getKey()), e.getValue()));
        }
        return out;
    }

    /**
     * B-9：该组件是否属于「受限页签」（料号在 BOM 树上已有下级时禁止加入）。
     *
     * <p>分支①（新模型）= {@code semantic == 'MATERIAL_ELEMENT'}；
     * 分支②（存量）= {@code tab_type ∈ {材质元素, 外购件}}，<b>与改动前逐字一致</b>（AC-25：
     * 存量 15 个外购件组件的行为不得变化）。
     *
     * <p>⚠️ 本方法只回答「哪些页签<b>触发</b>该校验」；「触发后怎么判」（该料号是否已有下级）
     * 是树结构判定，不在本类，也不因本次改造而变（AC-17）。
     */
    public boolean isRestrictedTab(UUID componentId, String tabType) {
        if (componentId == null) return isLegacyRestrictedTabType(tabType);
        Map<UUID, String> sem = builderSemantics(List.of(componentId));
        if (sem.containsKey(componentId)) {
            return SEMANTIC_MATERIAL_ELEMENT.equals(sem.get(componentId));
        }
        return isLegacyRestrictedTabType(tabType);
    }

    /** {@link #isRestrictedTab} 的批量版（同 N+1 纪律）。 */
    public Map<UUID, Boolean> isRestrictedTabBatch(Map<UUID, String> tabTypeByComponentId) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        if (tabTypeByComponentId == null || tabTypeByComponentId.isEmpty()) return out;
        Map<UUID, String> sem = builderSemantics(tabTypeByComponentId.keySet());
        for (Map.Entry<UUID, String> e : tabTypeByComponentId.entrySet()) {
            UUID cid = e.getKey();
            out.put(cid, sem.containsKey(cid)
                    ? SEMANTIC_MATERIAL_ELEMENT.equals(sem.get(cid))
                    : isLegacyRestrictedTabType(e.getValue()));
        }
        return out;
    }

    /**
     * B-17：{@code component.bom_recursive_expand} 的新写入源。
     *
     * @return {@code null} = 该组件<b>没有</b>取数配置器绑定（分支②，调用方保持既有写入逻辑）；
     *         非 null = 按绑定数据源的 {@code semantic == 'TREE'} 推导出的开关值
     */
    public Boolean builderTreeFlag(UUID componentId) {
        if (componentId == null) return null;
        Map<UUID, String> sem = builderSemantics(List.of(componentId));
        if (!sem.containsKey(componentId)) return null;
        return SEMANTIC_TREE.equals(sem.get(componentId));
    }

    /** 分支①的判定结果与分支②的合流点（本类唯一的「谁优先」定义）。 */
    private static boolean decideTree(String semantic, boolean builderBound, String tabType) {
        if (builderBound) return SEMANTIC_TREE.equals(semantic);   // 分支①优先（AC-27③）
        return isLegacyTreeTabType(tabType);                        // 分支②
    }

    // =========================================================================
    // 分支①：builder_config → (tabType, variantKey, dialect) → semantic_tab_view → semantic
    // =========================================================================

    /**
     * 批量解析分支① 的 semantic。
     *
     * @return componentId → semantic（{@link #SEMANTIC_TREE} / {@link #SEMANTIC_MATERIAL_ELEMENT} /
     *         {@link #SEMANTIC_PLAIN}）。<b>只有解析成功的组件才出现在 map 里</b>——未绑定取数
     *         配置器、或坐标解析不出对应 {@code semantic_tab_view} 行的组件一律不出现（调用方
     *         据此回退分支②）
     */
    public Map<UUID, String> builderSemantics(Collection<UUID> componentIds) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (componentIds == null || componentIds.isEmpty()) return out;
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(componentIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) return out;

        // SQL #1：一次 IN 取回这批组件里「由取数配置器产出」的视图。builder_version（integer 普通列）
        // 非 NULL 即新模型，与 builder_config IS NOT NULL 等价但更轻（写入点两列同时写）。
        // 同一组件理论上只有一条 builder 视图；真出现多条时取 updated_at 最新的那条（确定性优先，
        // 不做 findFirst() 那种「谁先扫到算谁」）。
        List<ComponentSqlView> views = ComponentSqlView.list(
                "componentId in ?1 and builderVersion is not null and status = ?2 order by updatedAt desc, id desc",
                ids, "ACTIVE");
        if (views.isEmpty()) return out;

        Map<UUID, ComponentSqlView> chosen = new LinkedHashMap<>();
        for (ComponentSqlView v : views) chosen.putIfAbsent(v.componentId, v);

        // SQL #2：语义图页签视图声明（45 行，全量取回后在内存里按三段坐标索引）。
        // 只有确实存在 builder 视图时才发这条查询 —— 现网 builder_version 为 0 行，本查询不触发。
        Map<String, SemanticTabView> byCoord = new LinkedHashMap<>();
        Map<String, List<SemanticTabView>> byTabAndVariant = new LinkedHashMap<>();
        for (SemanticTabView tv : SemanticTabView.<SemanticTabView>listAll()) {
            if (!"ACTIVE".equals(tv.status)) continue;
            String vk = tv.variantKey == null ? "" : tv.variantKey;
            byCoord.put(coordKey(tv.tabType, vk, tv.dialect), tv);
            byTabAndVariant.computeIfAbsent(tv.tabType + SEP + vk, k -> new ArrayList<>()).add(tv);
        }

        for (Map.Entry<UUID, ComponentSqlView> e : chosen.entrySet()) {
            String semantic = resolveSemantic(e.getKey(), e.getValue().builderConfig, byCoord, byTabAndVariant);
            if (semantic != null) out.put(e.getKey(), semantic);
        }
        return out;
    }

    /** 解析单个 {@code builder_config} JSONB → semantic；解析不出返回 null（调用方回退分支②）。 */
    private static String resolveSemantic(UUID componentId, String builderConfigJson,
                                           Map<String, SemanticTabView> byCoord,
                                           Map<String, List<SemanticTabView>> byTabAndVariant) {
        if (builderConfigJson == null || builderConfigJson.isBlank()) {
            LOG.warnf("[tab-semantic] comp=%s builder_version 非空但 builder_config 为空，回退 tab_type 判据", componentId);
            return null;
        }
        String tabType;
        String variantKey;
        String rawDialect;
        try {
            JsonNode node = MAPPER.readTree(builderConfigJson);
            tabType = text(node, "tabType");
            variantKey = text(node, "variantKey");
            rawDialect = text(node, "dialect");
        } catch (Exception ex) {
            LOG.warnf("[tab-semantic] comp=%s builder_config 解析失败(%s)，回退 tab_type 判据", componentId, ex.getMessage());
            return null;
        }
        if (tabType == null || tabType.isBlank()) {
            LOG.warnf("[tab-semantic] comp=%s builder_config.tabType 缺失，回退 tab_type 判据", componentId);
            return null;
        }
        String vk = variantKey == null ? "" : variantKey;
        String dialect = normalizeDialect(rawDialect);

        if (dialect != null) {
            SemanticTabView tv = byCoord.get(coordKey(tabType, vk, dialect));
            if (tv != null) {
                String sem = semanticOfGraphTabType(tv.tabType);
                LOG.debugf("[tab-semantic] comp=%s coord=(%s,%s,%s) hit graphTabType=%s -> semantic=%s",
                        componentId, tabType, vk, dialect, tv.tabType, "".equals(sem) ? "PLAIN" : sem);
                return sem;
            }
            LOG.warnf("[tab-semantic] comp=%s 三段坐标 (%s,%s,%s) 在 semantic_tab_view 无 ACTIVE 行，回退 tab_type 判据",
                    componentId, tabType, vk, dialect);
            return null;
        }

        // dialect 取不到（见 DIALECT 常量上的「待主线裁决」说明）：不 findFirst，改为「全部候选
        // semantic 必须一致」才采用 —— 取错方言在本判据上不可能发生，不一致则拒绝判定并留 WARN。
        List<SemanticTabView> candidates = byTabAndVariant.get(tabType + SEP + vk);
        if (candidates == null || candidates.isEmpty()) {
            LOG.warnf("[tab-semantic] comp=%s 坐标 (%s,%s) 在 semantic_tab_view 无 ACTIVE 行，回退 tab_type 判据",
                    componentId, tabType, vk);
            return null;
        }
        String agreed = null;
        for (SemanticTabView tv : candidates) {
            String s = semanticOfGraphTabType(tv.tabType);
            LOG.debugf("[tab-semantic] comp=%s coord=(%s,%s,dialect缺失) candidate graphTabType=%s -> semantic=%s",
                    componentId, tabType, vk, tv.tabType, "".equals(s) ? "PLAIN" : s);
            if (agreed == null) agreed = s;
            else if (!agreed.equals(s)) {
                LOG.warnf("[tab-semantic] comp=%s 坐标 (%s,%s) 缺 dialect 且候选行 semantic 不一致(%s vs %s)，"
                        + "拒绝按分支①判定，回退 tab_type 判据", componentId, tabType, vk, agreed, s);
                return null;
            }
        }
        return agreed;
    }

    /**
     * {@code builder_config.dialect} → {@code semantic_tab_view.dialect} 值域。
     *
     * <p>返回 {@code null} = 「无法唯一确定方言」，调用方按 {@link #resolveSemantic} 的一致性兜底处理。
     * 🚦 {@code COSTING} 之所以返回 null：{@code semantic_tab_view} 侧核价被拆成
     * {@code COST_BASIC} / {@code COST_DETAIL} 两个方言，一对二，映射不唯一。
     */
    private static String normalizeDialect(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String d = raw.trim().toUpperCase();
        switch (d) {
            case "QUOTE":
            case "COST_BASIC":
            case "COST_DETAIL":
                return d;
            default:
                return null;
        }
    }

    /**
     * {@code semantic_tab_view.tab_type} → semantic（S-1：只有两类锚点有额外语义）。
     *
     * <p>分支① 专用 —— 入参来自 {@code builder_config.tabType} / 图声明，<b>不是</b>
     * {@code component.tab_type}（那是分支②，见 {@link #isLegacyTreeTabType}）。
     * V417 后两侧取值恰好相同，但来源不同，别把两条判据合并。
     */
    private static String semanticOfGraphTabType(String graphTabType) {
        if (TAB_TYPE_TREE.equals(graphTabType)) return SEMANTIC_TREE;
        if (TAB_TYPE_TREE_PRE_V417.equals(graphTabType)) {
            LOG.warnf("[tab-semantic] 图侧 tab_type 仍是 V417 之前的 '%s'（该库的 semantic_tab_view 种子过期，"
                    + "应用 V417 即可修正）；本次按树页签处理", graphTabType);
            return SEMANTIC_TREE;
        }
        if (TAB_TYPE_MATERIAL_ELEMENT.equals(graphTabType)) return SEMANTIC_MATERIAL_ELEMENT;
        warnOnUnknownGraphTabType(graphTabType);
        return SEMANTIC_PLAIN;
    }

    /**
     * 值域漂移哨兵（task-260904 教训固化）：图侧 tab_type 若落在
     * {@link ComponentService#VALID_TAB_TYPES} 之外，一律 WARN。
     *
     * <p>🔑 <b>为什么必须有这一条</b>：V413→V417 那次，值一变本方法就静默落进「普通页签」，
     * BOM 树页签被判成非树、{@code bom_recursive_expand} 恒 false、{@code tree_ref} 保存 400，
     * 全程零报错，靠人肉排查才定位。判据的输入值域变化必须是<b>响的</b>。
     */
    private static void warnOnUnknownGraphTabType(String graphTabType) {
        if (graphTabType == null || graphTabType.isBlank()) return;
        if (ComponentService.VALID_TAB_TYPES.contains(graphTabType)) return;   // 主件/零件/外购件/费用类：正常无语义
        LOG.warnf("[tab-semantic] 图侧 tab_type='%s' 不在已知值域 %s 内 —— 语义图种子可能已漂移，"
                + "本次按「普通页签」处理；若它本应是树/材质元素页签，这里就是静默失效点",
                graphTabType, ComponentService.VALID_TAB_TYPES);
    }

    /** 坐标分隔符：用不可能出现在 tab_type / variant_key / dialect 里的控制字符，避免拼接歧义。 */
    private static final String SEP = "\u0001";

    private static String coordKey(String tabType, String variantKey, String dialect) {
        return tabType + SEP + variantKey + SEP + dialect;
    }


    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? null : v.asText(null);
    }
}
