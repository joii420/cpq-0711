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
        indexTabViews(SemanticTabView.<SemanticTabView>listAll(), byCoord, byTabAndVariant);

        for (Map.Entry<UUID, ComponentSqlView> e : chosen.entrySet()) {
            String semantic = resolveSemantic(e.getKey(), e.getValue().builderConfig, byCoord, byTabAndVariant);
            if (semantic != null) out.put(e.getKey(), semantic);
        }
        return out;
    }

    /** 把 {@code semantic_tab_view} 的 ACTIVE 行索引成「三段坐标 → 行」与「(tabType,variantKey) → 候选行」。 */
    private static void indexTabViews(Iterable<SemanticTabView> tabViews,
                                       Map<String, SemanticTabView> byCoord,
                                       Map<String, List<SemanticTabView>> byTabAndVariant) {
        for (SemanticTabView tv : tabViews) {
            if (!"ACTIVE".equals(tv.status)) continue;
            String vk = tv.variantKey == null ? "" : tv.variantKey;
            byCoord.put(coordKey(tv.tabType, vk, tv.dialect), tv);
            byTabAndVariant.computeIfAbsent(tv.tabType + SEP + vk, k -> new ArrayList<>()).add(tv);
        }
    }

    // =========================================================================
    // task-260915 B-6：坐标可解析性（导入预览用，只读）
    // =========================================================================

    /**
     * 一份 {@code builder_config} 的三段坐标在<b>本库</b> {@code semantic_tab_view} 里能不能解析到。
     *
     * @param resolved true = 解析得到；false = 解析不到
     * @param message  解析不到时的人话原因（缺哪个坐标）；解析得到时为 {@code null}
     */
    public record CoordCheck(boolean resolved, String message) { }

    /**
     * task-260915 B-6：<b>批量</b>判定一批 {@code builder_config} 的坐标在本库是否可解析。
     *
     * <p>用途：组件导入<b>预览</b>——包里带来的取数配置器坐标，在目标库的语义图里找不找得到。
     * 找不到不阻断导入（{@code builder_config} 原样落库），只是如实报出，由用户决定。
     *
     * <p>🚨 <b>只读</b>：全程只 SELECT，绝不写库。
     * <p>🚨 <b>N+1 硬指标</b>：恒 <b>1 条 SQL</b>（{@code semantic_tab_view} 全表一次），与入参个数无关；
     * 之后是纯内存索引查找。🚫 不许放进按组件的循环里调。
     *
     * <p>🚫 <b>不要另写一份坐标解析</b>：本方法与 {@link #builderSemantics} 共用同一个
     * {@code parseCoordDetailed} + 同一套索引口径，各写一份必然漂移。
     *
     * @param builderConfigJsons 与调用方列表<b>下标一一对应</b>的 builder_config JSON；
     *                           元素为 null/空白时该位返回 {@code resolved=false} 并说明为空
     * @return 与入参<b>等长、同下标</b>的结果列表
     */
    public List<CoordCheck> checkBuilderCoords(List<String> builderConfigJsons) {
        List<CoordCheck> out = new ArrayList<>();
        if (builderConfigJsons == null || builderConfigJsons.isEmpty()) return out;

        // SQL #1（本方法唯一一条查询）：语义图页签视图声明，全量取回后在内存里按坐标索引。
        Map<String, SemanticTabView> byCoord = new LinkedHashMap<>();
        Map<String, List<SemanticTabView>> byTabAndVariant = new LinkedHashMap<>();
        indexTabViews(SemanticTabView.<SemanticTabView>listAll(), byCoord, byTabAndVariant);

        // 纯内存分发：本循环体内没有任何查询/懒加载（byCoord/byTabAndVariant 已在上面一次取全）
        for (String json : builderConfigJsons) {
            CoordParse parsed = parseCoordDetailed(null, json);
            if (parsed.coord() == null) {
                out.add(new CoordCheck(false, parsed.reason()));
                continue;
            }
            Coord coord = parsed.coord();
            if (coord.dialect() != null) {
                SemanticTabView tv = byCoord.get(coordKey(coord.tabType(), coord.variantKey(), coord.dialect()));
                out.add(tv != null ? new CoordCheck(true, null) : new CoordCheck(false,
                        "目标库的语义图里没有坐标 (页签类型=" + coord.tabType()
                        + ", 变体=" + (coord.variantKey().isEmpty() ? "(空)" : coord.variantKey())
                        + ", 方言=" + coord.dialect() + ") 对应的页签声明"));
                continue;
            }
            // 方言取不到：与 resolveSemantic 同一条兜底口径——按 (tabType, variantKey) 找候选行
            List<SemanticTabView> candidates = byTabAndVariant.get(coord.tabType() + SEP + coord.variantKey());
            out.add(candidates != null && !candidates.isEmpty()
                    ? new CoordCheck(true, null)
                    : new CoordCheck(false, "目标库的语义图里没有坐标 (页签类型=" + coord.tabType()
                            + ", 变体=" + (coord.variantKey().isEmpty() ? "(空)" : coord.variantKey())
                            + ") 对应的页签声明"));
        }
        return out;
    }

    /**
     * {@code builder_config} JSONB 里的三段坐标。解析不出返回 {@code null}（调用方回退分支②）。
     *
     * <p>抽出来是为了让「按坐标反查 semantic_tab_view」这件事只有<b>一份</b>解析实现 ——
     * task-260907 B-4 的数据源名反查用的是同一份坐标，各写一份必然漂移。
     */
    private record Coord(String tabType, String variantKey, String dialect) { }

    private static Coord parseCoord(UUID componentId, String builderConfigJson) {
        return parseCoordDetailed(componentId, builderConfigJson).coord();
    }

    /**
     * {@link #parseCoord} 的带原因版本 —— <b>坐标解析的唯一实现</b>，{@code parseCoord} 只是丢掉原因的
     * 薄包装。抽出 {@code reason} 是给 task-260915 B-6 的导入预览用的：预览要把「缺哪一段坐标」
     * 当人话报给用户，而不是只回一个 null。
     *
     * @param componentId 仅用于日志；预览场景（组件尚未落库）传 {@code null}
     * @return {@code coord} 非 null = 解析成功；否则 {@code reason} 给出人话原因
     */
    private static CoordParse parseCoordDetailed(UUID componentId, String builderConfigJson) {
        if (builderConfigJson == null || builderConfigJson.isBlank()) {
            LOG.warnf("[tab-semantic] comp=%s builder_version 非空但 builder_config 为空", componentId);
            return new CoordParse(null, "取数配置器信息(builder_config)为空");
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
            LOG.warnf("[tab-semantic] comp=%s builder_config 解析失败(%s)", componentId, ex.getMessage());
            return new CoordParse(null, "取数配置器信息(builder_config)不是合法 JSON：" + ex.getMessage());
        }
        if (tabType == null || tabType.isBlank()) {
            LOG.warnf("[tab-semantic] comp=%s builder_config.tabType 缺失", componentId);
            return new CoordParse(null, "取数配置器信息里缺少页签类型坐标(builder_config.tabType)");
        }
        return new CoordParse(
                new Coord(tabType, variantKey == null ? "" : variantKey, normalizeDialect(rawDialect)),
                null);
    }

    /** {@link #parseCoordDetailed} 的返回：{@code coord} 非 null 即成功，否则 {@code reason} 说明原因。 */
    private record CoordParse(Coord coord, String reason) { }

    /** 解析单个 {@code builder_config} JSONB → semantic；解析不出返回 null（调用方回退分支②）。 */
    private static String resolveSemantic(UUID componentId, String builderConfigJson,
                                           Map<String, SemanticTabView> byCoord,
                                           Map<String, List<SemanticTabView>> byTabAndVariant) {
        Coord coord = parseCoord(componentId, builderConfigJson);
        if (coord == null) {
            LOG.warnf("[tab-semantic] comp=%s 坐标解析不出，回退 tab_type 判据", componentId);
            return null;
        }
        String tabType = coord.tabType();
        String vk = coord.variantKey();
        String dialect = coord.dialect();

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

    // =========================================================================
    // task-260907 B-4（F-2 / AC-3）：组件 → 所绑「数据源名」
    // =========================================================================

    @jakarta.inject.Inject
    com.cpq.semanticgraph.service.SemanticGraphLoader graphLoader;

    /**
     * 批量解析「该组件绑的是哪个数据源」的<b>用户可见名</b>（组件列表徽章，api.md §3.1）。
     *
     * <p>取值链：{@code component_sql_view.builder_config} 的三段坐标 →
     * {@code semantic_tab_view} → 其<b>锚点节点</b>的 {@code display_name}
     * （与 {@code FieldTreeBuilder#buildAvailableSources} 的 {@code label} <b>同一个取值口径</b>，
     * 这样列表徽章上的字与配置器数据源下拉里那一行字逐字相同）。
     *
     * <p>🚨 <b>N+1 硬指标</b>：本方法<b>恒 1 条 SQL</b>（{@code component_sql_view} 的一次 IN 批量），
     * 与入参组件数无关 —— 语义图走 {@link com.cpq.semanticgraph.service.SemanticGraphLoader} 的
     * <b>内存不可变快照</b>，零查库。222 个组件 = 1 条 SQL，2 个组件也是 1 条。
     * 🚫 不许把它放进按组件的循环里调用。
     *
     * @return componentId → 数据源名；<b>只有解析成功的组件才出现在 map 里</b> ——
     *         未绑取数配置器（{@code builder_version} 为 NULL）或坐标查不到行的一律不出现，
     *         调用方按「缺键 = null = 前端渲染成『—』」处理（用户 2026-09-07 裁决）
     */
    public Map<UUID, String> builderDataSourceLabels(Collection<UUID> componentIds) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (componentIds == null || componentIds.isEmpty()) return out;
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(componentIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) return out;

        // SQL #1（本方法唯一一条查询）：这批组件里「由取数配置器产出」的视图。
        // 同一组件出现多条 builder 视图时取 updated_at 最新的那条（确定性优先，与 builderSemantics 同规则）。
        List<ComponentSqlView> views = ComponentSqlView.list(
                "componentId in ?1 and builderVersion is not null and status = ?2 order by updatedAt desc, id desc",
                ids, "ACTIVE");
        if (views.isEmpty()) return out;
        Map<UUID, ComponentSqlView> chosen = new LinkedHashMap<>();
        for (ComponentSqlView v : views) chosen.putIfAbsent(v.componentId, v);

        // 内存快照，0 条 SQL（SemanticGraphLoader 启动时全量装载 + 写端点后整体换引用）
        com.cpq.semanticgraph.service.SemanticGraphSnapshot snap = graphLoader.get();
        Map<String, SemanticTabView> byCoord = new LinkedHashMap<>();
        Map<String, List<SemanticTabView>> byTabAndVariant = new LinkedHashMap<>();
        for (SemanticTabView tv : snap.tabViews) {
            if (!"ACTIVE".equals(tv.status)) continue;
            String vk = tv.variantKey == null ? "" : tv.variantKey;
            byCoord.put(coordKey(tv.tabType, vk, tv.dialect), tv);
            byTabAndVariant.computeIfAbsent(tv.tabType + SEP + vk, k -> new ArrayList<>()).add(tv);
        }

        // 纯内存分发：本循环体内**没有任何查询/懒加载**（snap 是不可变 POJO 图，chosen 已在上面取全）
        for (Map.Entry<UUID, ComponentSqlView> e : chosen.entrySet()) {
            UUID cid = e.getKey();
            Coord coord = parseCoord(cid, e.getValue().builderConfig);
            if (coord == null) continue;
            String label = null;
            if (coord.dialect() != null) {
                SemanticTabView tv = byCoord.get(coordKey(coord.tabType(), coord.variantKey(), coord.dialect()));
                label = anchorDisplayName(snap, tv);
            } else {
                // 方言缺失：与 resolveSemantic 同一条兜底规则 —— 不 findFirst 静默取一行，
                // 而是「全部候选必须给出同一个名字」才采用（三方言的同名页签 display_name 本就相同）。
                List<SemanticTabView> candidates = byTabAndVariant.get(coord.tabType() + SEP + coord.variantKey());
                if (candidates != null) {
                    for (SemanticTabView tv : candidates) {
                        String n = anchorDisplayName(snap, tv);
                        if (n == null) { label = null; break; }
                        if (label == null) { label = n; }
                        else if (!label.equals(n)) {
                            LOG.warnf("[tab-semantic] comp=%s 坐标 (%s,%s) 缺 dialect 且候选行数据源名不一致"
                                    + "(%s vs %s)，徽章留空", cid, coord.tabType(), coord.variantKey(), label, n);
                            label = null;
                            break;
                        }
                    }
                }
            }
            if (label != null && !label.isBlank()) out.put(cid, label);
        }
        return out;
    }

    /**
     * 把 {@link com.cpq.component.dto.ComponentDTO#dataSourceLabel} 填上（整批一次，B-4 唯一写入点）。
     *
     * <p>🚨 <b>三个读端点都必须调它</b>，否则会出现「列表有徽章、目录树没有」这种一半生效的形态：
     * <ul>
     *   <li>{@code GET /api/cpq/component-directories} → {@code ComponentDirectoryService#buildTree}
     *       —— <b>组件管理页真正驱动徽章的就是这一条</b>（前端 {@code listDirectories} 原样透传，无 mapper）；</li>
     *   <li>{@code GET /api/cpq/components} → {@code ComponentService#list}；</li>
     *   <li>{@code GET /api/cpq/components/{id}} → {@code ComponentService#getById}。</li>
     * </ul>
     *
     * <p>🚨 <b>N+1</b>：整批一次解析（{@link #builderDataSourceLabels} 恒 1 条 SQL），
     * 下面的循环体是纯内存 Map 分发。目录树一次带 222 个组件也只有 1 条 SQL。
     */
    public void applyDataSourceLabels(Collection<com.cpq.component.dto.ComponentDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) return;
        List<UUID> ids = dtos.stream()
                .map(d -> d.id)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;
        Map<UUID, String> labels = builderDataSourceLabels(ids);
        // 🚫 labels 为空也要走完循环：语义是「全部未绑数据源」⇒ 全部保持 null（前端渲染「—」），
        //    提前 return 与走完循环等价，但不提前 return 少一处将来加逻辑时的分叉。
        for (com.cpq.component.dto.ComponentDTO d : dtos) {
            d.dataSourceLabel = labels.get(d.id);   // 缺键 = 未绑 = null（AC-3②）
        }
    }

    private static String anchorDisplayName(com.cpq.semanticgraph.service.SemanticGraphSnapshot snap,
                                            SemanticTabView tv) {
        if (tv == null) return null;
        com.cpq.semanticgraph.entity.SemanticNode anchor = snap.nodeById.get(tv.anchorNodeId);
        return anchor == null ? null : anchor.displayName;
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
     *
     * <p>🔓 <b>2026-09-06（task-260904 B-1）改为 public static</b>：{@code FieldTreeBuilder}
     * 组装 {@code availableSources[].semantic}（api.md §1.2）时必须给出同一个答案 ——
     * 前端按它分支渲染（树提示 / 价格策略组），后端按它判树（{@link #isTreeTab}）。
     * 两处若各写一份映射，就会出现「面板说这是树、渲染判它不是树」且两边都不报错。
     * 🚫 不要在别处再写一份 {@code tab_type → semantic} 的映射。
     *
     * @return {@link #SEMANTIC_TREE} / {@link #SEMANTIC_MATERIAL_ELEMENT} /
     *         {@link #SEMANTIC_PLAIN}（空串，<b>不是 null</b>；JSON 侧要 null 的调用方自行转换）
     */
    public static String semanticOfGraphTabType(String graphTabType) {
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
