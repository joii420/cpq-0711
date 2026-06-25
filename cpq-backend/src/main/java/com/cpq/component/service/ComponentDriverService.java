package com.cpq.component.service;
import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.Component;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.dataloader.PartVersionContext;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Y1.5 组件�?driver 路径展开服务�?
 *
 * <p>给定 component(�?dataDriverPath + fields[BASIC_DATA].basic_data_path):
 * <ol>
 *   <li>�?dataDriverPath 查询 N �?driver rows)</li>
 *   <li>对每一�?迭代所�?BASIC_DATA 字段,�?driver 行作为隐�?JOIN 谓词注入</li>
 *   <li>组装 ExpandDriverResponse 返回,前端�?row 渲染 N �?/li>
 * </ol>
 *
 * <p>�?dataDriverPath �?rowCount=0 直接返回(前端走单行老逻辑)
 *
 * <p>进程级缓存：TTL=30s after-write，maximumSize=5000�?
 * key = "componentId:customerId:partNo"（null �?"_" 占位）�?
 * 基础数据导入完成后调�?{@link #evictAll()} 清空，保证导入数据立即可见�?
 */
@ApplicationScoped
public class ComponentDriverService {

    private static final Logger LOG = Logger.getLogger(ComponentDriverService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 进程�?Caffeine 缓存（ApplicationScoped Bean 生命周期内单例）�?
     * key: "componentId:customerId:partNo"（null 部分�?"_" 占位�?
     * TTL: 30 �?after write，maximumSize: 5000
     */
    private final Cache<String, ExpandDriverResponse> expandCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(5000)
            .build();

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    @Inject
    GlobalVariableService globalVariableService;

    // 加产品整份快照 Phase 2（docs/方案-加产品整份快照.md）：渲染读报价单快照(snapshot_rows)。
    @Inject
    jakarta.persistence.EntityManager em;

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    /** V190+: 合成 key 前缀, 标识 basicDataValues �?全局变量行级�?条目 (避免�?BNF path key 冲突) */
    public static final String GVAR_KEY_PREFIX = "@gvar:";

    public static String gvarKey(String code) {
        return GVAR_KEY_PREFIX + code;
    }

    /**
     * 构建缓存 key�?-arg，含 partVersion 维度）�?
     * key 格式: "componentId:customerId:partNo:partVersion"（null �?"_" 占位�?
     */
    public static String cacheKey(UUID componentId, UUID customerId, String partNo, Integer partVersion) {
        return componentId + ":"
                + (customerId != null ? customerId.toString() : "_")
                + ":" + (partNo != null && !partNo.isBlank() ? partNo : "_")
                + ":" + (partVersion != null ? partVersion.toString() : "_");
    }

    /**
     * 构建缓存 key�?-arg 向后兼容重载，partVersion=null）�?
     * 委托�?4-arg 重载，key 末段�?"_"�?
     */
    public static String cacheKey(UUID componentId, UUID customerId, String partNo) {
        return cacheKey(componentId, customerId, partNo, null);
    }

    /**
     * 清空所有缓存条目。在基础数据导入事务提交后调用，让新数据立即可见�?
     */
    public void evictAll() {
        long sizeBefore = expandCache.estimatedSize();
        expandCache.invalidateAll();
        LOG.infof("[expand-driver cache] evictAll called, estimated entries before evict=%d", sizeBefore);
    }

    /**
     * 定向清除某 lineItem 的所有 driver 展开缓存条目（key 含 ":li&lt;lineItemId&gt;" 维度）。
     * <p>用于草稿态打开重刷（{@code refreshDraftQuoteCards}）前强制重查最新 SQL —— 后台基础数据被直接
     * 改库（绕过 app 导入流程，未调 {@link #evictAll()}）时，30s TTL 内缓存仍命中旧值导致快照刷不出新数据。
     * 只清本单行的条目，不误伤其它报价单/用户的缓存。
     */
    public void evictForLineItem(UUID lineItemId) {
        if (lineItemId == null) return;
        String tag = ":li" + lineItemId.toString().replace("-", "");
        java.util.List<String> toEvict = new java.util.ArrayList<>();
        for (String k : expandCache.asMap().keySet()) {
            if (k.contains(tag)) toEvict.add(k);
        }
        if (!toEvict.isEmpty()) {
            expandCache.invalidateAll(toEvict);
            LOG.infof("[expand-driver cache] evictForLineItem li=%s evicted=%d", lineItemId, toEvict.size());
        }
    }

    /**
     * 向后兼容 3-arg 重载，partVersion=null（不注入版本过滤）�?
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo) {
        return expand(componentId, customerId, partNo, null);
    }

    /**
     * 4-arg 重载：含 partVersion，set/clear PartVersionContext 确保 ImplicitJoinRewriter
     * �?DataLoader 查询时注�?AND part_version=N 谓词，避免历史版本数据叠加重复�?
     *
     * @param partVersion 料号版本号；null = 不注入版本过滤（行为等同旧版�?
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion) {
        return expand(componentId, customerId, partNo, partVersion, null, null);
    }

    /**
     * V195 hotfix: �?snapshot override �?expand. �?template snapshot 作为 driver_path/fields 真理�?
     * 不再依赖 component �?(避免�?component 被多模板共用导致 driver 不一�?.
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                       String overrideDataDriverPath, String overrideFieldsJson) {
        return expand(componentId, customerId, partNo, partVersion, overrideDataDriverPath, overrideFieldsJson, null);
    }

    /**
     * Bug B: �?lineItemId �?expand 重载�?
     *
     * <p>lineItemId 非空时，driver 查询分两�?
     * <ol>
     *   <li>先查 quotation_line_item_id = lineItemId 的专属工序行（通过 ImplicitJoinRewriter 注入谓词�?/li>
     *   <li>若无结果，fallback �?quotation_line_item_id IS NULL 的主数据行（原有逻辑�?/li>
     * </ol>
     * 这使�?hf_part_no 的不�?lineItem 工序互不干扰�?
     *
     * <p>lineItemId = null 时行为与旧版完全等价（backward compatible）�?
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                       String overrideDataDriverPath, String overrideFieldsJson,
                                       UUID lineItemId) {
        return expand(componentId, customerId, partNo, partVersion, overrideDataDriverPath, overrideFieldsJson, lineItemId, null);
    }

    /**
     * 8-arg: lineItemId + compositeType. compositeType="COMPOSITE" + v_composite_child_* path
     * -> skip lineItemId injection (aggregate child processes by hf_part_no).
     * compositeType="SIMPLE" or null -> inject lineItemId (restrict to current lineItem rows only).
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                       String overrideDataDriverPath, String overrideFieldsJson,
                                       UUID lineItemId, String compositeType) {
        return expand(componentId, customerId, partNo, partVersion, overrideDataDriverPath, overrideFieldsJson,
                lineItemId, compositeType, null);
    }

    /**
     * 9-arg: lineItemId + compositeType + childLineItemIds.
     *
     * <p>childLineItemIds 仅在 compositeType="COMPOSITE" + v_composite_child_* 路径时生效：
     * 向查询注入 quotation_line_item_id IN (childLineItemIds) OR quotation_line_item_id IS NULL
     * 谓词，限定只返回当前报价单子件自己的工序行（消除历史累积）。
     *
     * <p>childLineItemIds 为 null/空时降级为旧行为（不注入 IN 谓词）。
     */
    /**
     * 加产品整份快照 Phase 2（docs/方案-加产品整份快照.md）：渲染入口。
     * <p>命中报价单快照(quotation_line_component_data.snapshot_rows,按 lineItemId×componentId)→
     * 反序列化冻结行直返(包括"空快照=空渲染",不再回退基础);snapshot_rows 为 NULL(无快照,
     * 如存量老行)→ 回退实时 {@link #expand}。基础表变化不影响已快照的报价行。
     * <p>注意:写快照的 {@code ConfigureSnapshotService} 仍调 live {@link #expand} 取基础,不走本方法,避免循环。
     */
    public ExpandDriverResponse expandWithSnapshot(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                                   String overrideDataDriverPath, String overrideFieldsJson,
                                                   UUID lineItemId, String compositeType, List<UUID> childLineItemIds) {
        // 调试捕获 SQL 时旁路报价单快照直返, 强制走实时 driver 以触发并记录最终 SQL。
        if (lineItemId != null && componentId != null
                && !com.cpq.datasource.sqlview.SqlDebugContext.isActive()) {
            try {
                // 批量预载模式(batch-expand 整单一次 IN 查后设入 ThreadLocal):命中上下文即用,不再逐 task 查库;
                //   上下文已设但该对不在 map(=无快照)→ json=null,落到下方实时 expand;未设 → 回落逐 task 查(零破坏)。
                String json;
                if (com.cpq.formula.dataloader.SnapshotRowsContext.isSet()) {
                    json = com.cpq.formula.dataloader.SnapshotRowsContext.get(lineItemId, componentId);
                } else {
                    @SuppressWarnings("unchecked")
                    List<Object> snap = em.createNativeQuery(
                            "SELECT snapshot_rows FROM quotation_line_component_data " +
                            "WHERE line_item_id = :lid AND component_id = :cid AND snapshot_rows IS NOT NULL LIMIT 1")
                        .setParameter("lid", lineItemId).setParameter("cid", componentId)
                        .getResultList();
                    json = (!snap.isEmpty() && snap.get(0) != null) ? snap.get(0).toString() : null;
                }
                if (json != null) {
                    List<ExpandDriverResponse.Row> snapRows = (json.isBlank())
                            ? new ArrayList<>()
                            : SNAPSHOT_MAPPER.readValue(json, new TypeReference<List<ExpandDriverResponse.Row>>() {});
                    ExpandDriverResponse resp = new ExpandDriverResponse();
                    resp.rows = snapRows != null ? snapRows : new ArrayList<>();
                    resp.rowCount = resp.rows.size();
                    resp.driverPath = "snapshot";
                    return resp;
                }
            } catch (Exception e) {
                LOG.warnf("[snapshot-read] line=%s comp=%s 读快照失败,回退实时: %s", lineItemId, componentId, e.getMessage());
            }
        }
        return expand(componentId, customerId, partNo, partVersion, overrideDataDriverPath, overrideFieldsJson,
                lineItemId, compositeType, childLineItemIds);
    }

    /**
     * 批量预取整单所有 (line_item_id, component_id) 的 snapshot_rows —— 一次 IN 查替代 batch-expand
     * Phase 1 的逐 task SELECT(N+1)。返回 key={@code lineItemId|componentId}(见 {@link com.cpq.formula.dataloader.SnapshotRowsContext#key})
     * → snapshot_rows 文本的 Map(仅含 snapshot_rows 非 NULL 的对;无快照的对不在 map → 调用侧落实时 expand)。
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, String> prefetchSnapshotRows(java.util.Collection<UUID> lineItemIds) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (lineItemIds == null || lineItemIds.isEmpty()) return out;
        List<Object[]> rows = em.createNativeQuery(
                "SELECT line_item_id, component_id, snapshot_rows FROM quotation_line_component_data " +
                "WHERE line_item_id IN (:lids) AND snapshot_rows IS NOT NULL")
            .setParameter("lids", lineItemIds)
            .getResultList();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null || r[2] == null) continue;
            UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
            UUID cid = (r[1] instanceof UUID u) ? u : UUID.fromString(r[1].toString());
            out.put(com.cpq.formula.dataloader.SnapshotRowsContext.key(lid, cid), r[2].toString());
        }
        return out;
    }

    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                       String overrideDataDriverPath, String overrideFieldsJson,
                                       UUID lineItemId, String compositeType, List<UUID> childLineItemIds) {
        // 阶段 3: 设 SqlViewRuntimeContext ThreadLocal，让 BNF path $xxx 引用能拿到 currentComponentId
        // （quotation/template 上下文留 null，本入口只知道 componentId 维度；
        // QuotationService.submit / 渲染期上层可进一步 setNested 补 quotationId+status）
        com.cpq.datasource.sqlview.SqlViewRuntimeContext.Snapshot _prevSqlViewCtx =
                com.cpq.datasource.sqlview.SqlViewRuntimeContext.setNested(componentId, null, null, null);
        try {
        // cache key �?override 哈希避免不同 snapshot 共享 cache 串号
        // Bug B: �?lineItemId 维度，防止同 partNo 不同 lineItem 的工�?cache 串行
        String overrideTag = "";
        if (overrideDataDriverPath != null || overrideFieldsJson != null) {
            overrideTag = ":ov" + Integer.toHexString(java.util.Objects.hash(overrideDataDriverPath, overrideFieldsJson));
        }
        String lineItemTag = (lineItemId != null) ? ":li" + lineItemId.toString().replace("-", "") : "";
        // COMPOSITE 子件 IN 谓词维度：加入 childLineItemIds 哈希，避免相同父级不同子件集的缓存串
        String childTag = (childLineItemIds != null && !childLineItemIds.isEmpty())
                ? ":cld" + Integer.toHexString(childLineItemIds.hashCode())
                : "";
        String key = cacheKey(componentId, customerId, partNo, partVersion) + overrideTag + lineItemTag + childTag;
        // 调试捕获 SQL 时旁路缓存读取, 强制重算以触发 SqlViewExecutor 记录最终 SQL。
        ExpandDriverResponse cached = com.cpq.datasource.sqlview.SqlDebugContext.isActive()
                ? null : expandCache.getIfPresent(key);
        if (cached != null) {
            LOG.debugf("[expand-driver cache] HIT key=%s", key);
            return cached;
        }
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }

        // EXCEL 组件不参与 driver expand：它无 dataDriverPath，但仍可能含 BASIC_DATA 字段，
        // 若不显式拦截会落入下方「产品级单行虚拟 driver」分支被误展开。Excel 视图渲染走独立通道(Phase 3)。
        if ("EXCEL".equals(component.componentType)) {
            LOG.infof("[Y1.5 expand-driver] componentType=EXCEL, skip driver expand (component=%s)", component.code);
            ExpandDriverResponse excelResp = new ExpandDriverResponse();
            excelResp.driverPath = component.dataDriverPath;
            excelResp.rows = new ArrayList<>();
            excelResp.rowCount = 0;
            return excelResp;
        }

        // V195 override: 优先�?snapshot 提供�?driver_path / fields
        String effectiveDriverPath = (overrideDataDriverPath != null && !overrideDataDriverPath.isBlank())
                ? overrideDataDriverPath : component.dataDriverPath;
        String effectiveFieldsJson = (overrideFieldsJson != null && !overrideFieldsJson.isBlank())
                ? overrideFieldsJson : component.fields;

        LOG.infof("[Y1.5 expand-driver] componentId=%s code=%s dataDriverPath=%s (override=%s) partNo=%s customerId=%s partVersion=%s lineItemId=%s compositeType=%s",
                componentId, component.code, effectiveDriverPath,
                (overrideDataDriverPath != null), partNo, customerId, partVersion, lineItemId, compositeType);

        ExpandDriverResponse resp = new ExpandDriverResponse();
        resp.driverPath = effectiveDriverPath;
        resp.rows = new ArrayList<>();

        if (effectiveDriverPath == null || effectiveDriverPath.isBlank()) {
            // hotfix: 没有 dataDriverPath 的组件视为「产品级单行�?�?�?(partNo, customerId) 作虚�?
            // driver row, 解所�?BASIC_DATA 字段路径�?basicDataValues. 前端�?driver 分支取�?
            // 绕开 usePathFormulaCache �?globalPathCache 路径 (后者偶�?cache key 不一致问�?.
            //
            // 这样 COMP-CFG-MATERIAL-RECIPE 等产品级组件 (v_part_material_recipe.code �? 直接通过
            // 行级 basicDataValues 拿到�? 不再永远"加载�?.
            List<String> basicDataPaths = parseBasicDataPaths(effectiveFieldsJson);
            List<GvarDefaultTask> gvarTasks = parseGvarDefaultTasks(effectiveFieldsJson);
            if (basicDataPaths.isEmpty() && gvarTasks.isEmpty()) {
                resp.rowCount = 0;
                LOG.infof("[Y1.5 expand-driver] dataDriverPath EMPTY + no BASIC_DATA, skip (component=%s)", component.code);
                if (!com.cpq.datasource.sqlview.SqlDebugContext.isActive()) expandCache.put(key, resp);
                return resp;
            }
            // 虚拟 driver row: 仅含 partNo / customerId, �?ImplicitJoinRewriter 能注入谓�?
            Map<String, Object> virtualRow = new LinkedHashMap<>();
            if (partNo != null && !partNo.isBlank()) {
                virtualRow.put("hf_part_no", partNo);
                virtualRow.put("part_no", partNo);
            }
            if (customerId != null) {
                virtualRow.put("customer_id", customerId);
            }
            // default_source.BASIC_DATA: 先把引用的 $view 整行(中文 key 安全)merge 进 virtualRow,
            // 使下方 evaluatePath 短路命中 driverRow.get(中文列), 绕开 $view 单列路径的 ASCII 校验。
            for (String viewBase : parseBasicDataDefaultViewBases(effectiveFieldsJson)) {
                try {
                    List<Map<String, Object>> vrows = dataLoader.loadByPath(viewBase, null, partNo, customerId).get();
                    if (vrows != null && !vrows.isEmpty() && vrows.get(0) != null) {
                        for (Map.Entry<String, Object> e : vrows.get(0).entrySet()) {
                            // putIfAbsent: 不覆盖已注入的 hf_part_no/part_no/customer_id; 多视图同名列时先列出的视图先赢(实际极少冲突)。
                            virtualRow.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    LOG.warnf("[default-source BASIC_DATA] merge view row interrupted base=%s partNo=%s: %s",
                            viewBase, partNo, ex.getMessage());
                } catch (Exception ex) {
                    LOG.warnf("[default-source BASIC_DATA] merge view row failed base=%s partNo=%s: %s",
                            viewBase, partNo, ex.getMessage());
                }
            }
            ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
            row.driverRow = virtualRow;
            row.basicDataValues = new LinkedHashMap<>();
            for (String fieldPath : basicDataPaths) {
                Object value = evaluatePath(fieldPath, virtualRow, customerId, partNo);
                row.basicDataValues.put(fieldPath, value);
            }
            for (GvarDefaultTask task : gvarTasks) {
                row.basicDataValues.put(gvarKey(task.code), resolveGvarForRow(task, virtualRow));
            }
            resp.rows.add(row);
            resp.rowCount = 1;
            LOG.infof("[Y1.5 expand-driver] no-driver virtual single row (component=%s, basicDataPaths=%d)",
                    component.code, basicDataPaths.size());
            if (!com.cpq.datasource.sqlview.SqlDebugContext.isActive()) expandCache.put(key, resp);
            return resp;
        }

        // 注入 partVersion �?ThreadLocal，DataLoader/ImplicitJoinRewriter 在版本化表查询时读取
        PartVersionContext.set(partVersion);
        try {
            // 1. �?driver �?�?�?(partNo, customerId) 作为基础上下文隐�?JOIN
            // Bug B 修复 (2026-05-20):
            //   �?lineItemId 非空，优先查�?lineItem 专属工序行�?
            //   若专属行不存在（竞�? configure 事务尚未提交）→ 直接返回 EMPTY (rowCount=0)�?
            //   **不再 fallback �?quotation_line_item_id IS NULL 主数据行**�?
            //   理由: fallback 结果会被 Caffeine �?lineItemId-tag key 缓存，导致后续即�?
            //   configure 已提交、专属行已存在，缓存命中仍返回错误的全量主数�?(14/15 �?�?
            //   前端 invalidateDriverExpansions 调用会清掉此 EMPTY 缓存条目，触发下一�?batchExpand
            //   重查，届�?configure 事务已提交，专属行存在，返回正确�?1 行�?
            List<Map<String, Object>> driverRows;
            try {
                // COMPOSITE 聚合视图 (v_composite_child_*) 通过 mat_bom.hf_part_no = 父级partNo
                // 关联子件工序，quotation_line_item_id 谓词对聚合视图无意义（子件工序行绑定
                // 的是子件自身 lineItemId，不是父级 lineItemId），不应注入。
                boolean isCompositeAggregateView = effectiveDriverPath != null
                        && (effectiveDriverPath.contains("v_composite_child_")
                            || effectiveDriverPath.contains("composite_child_"));   // 兼容 $<mirror> 形式
                // Fix: only skip lineItemId injection when BOTH conditions hold:
                //   1) path is a composite aggregate view (v_composite_child_*)
                //   2) caller explicitly says this is a COMPOSITE parent lineItem
                // SIMPLE lineItems using v_composite_child_* path must still inject lineItemId
                // to restrict results to their own process rows, not all 171+ historical rows.
                boolean isCompositeParent = "COMPOSITE".equals(compositeType);

                if (lineItemId != null && !(isCompositeAggregateView && isCompositeParent)) {
                    // 构造含 quotation_line_item_id 的 driverRow hint，让 ImplicitJoinRewriter 注入谓词
                    Map<String, Object> lineItemHint = new LinkedHashMap<>();
                    lineItemHint.put("quotation_line_item_id", lineItemId);
                    List<Map<String, Object>> lineItemRows =
                        dataLoader.loadByPath(effectiveDriverPath, lineItemHint, partNo, customerId).get();
                    if (lineItemRows != null && !lineItemRows.isEmpty()) {
                        // 专属行存在 → 使用
                        driverRows = lineItemRows;
                        LOG.debugf("[Bug B expand-driver] lineItemId=%s hit %d specialized rows for partNo=%s",
                                lineItemId, lineItemRows.size(), partNo);
                    } else {
                        // 专属行不存在 (可能是 configure 事务竞态未提交) → 返回 EMPTY，不 fallback 主数据。
                        // Caffeine 缓存为 EMPTY（TTL 内有效）；invalidateDriverExpansions 调用后清除，
                        // 下一次 batchExpand 在 configure 已提交后正确返回专属行。
                        LOG.infof("[Bug B expand-driver] lineItemId=%s no specialized rows -> EMPTY (no fallback) for partNo=%s path=%s",
                                lineItemId, partNo, effectiveDriverPath);
                        resp.rowCount = 0;
                        if (!com.cpq.datasource.sqlview.SqlDebugContext.isActive()) expandCache.put(key, resp);
                        return resp;
                    }
                } else if (isCompositeAggregateView && isCompositeParent
                        && childLineItemIds != null && !childLineItemIds.isEmpty()
                        && effectiveDriverPath != null
                        && (effectiveDriverPath.contains("v_composite_child_processes")
                            || effectiveDriverPath.contains("composite_child_processes"))
                        // 仅旧视图 v_composite_child_processes 有 quotation_line_item_id 列 (V207/V209)。
                        // 新 mirror($composite_child_processes_mirror) 无该列 → 注入 IN 谓词 = 引用不存在的列
                        // → COMBO 父级工序展开返 0 行（前端渲染 13 空行）。且新 mirror 已用 :quotationId 自我隔离
                        // (branch2 仅聚合本报价单子件工序)，不存在旧视图的"历史累积"问题 → 直接走 full aggregate。
                        && !effectiveDriverPath.contains("mirror")) {
                    // COMPOSITE 父级 + 工序聚合视图 + 子件 lineItemId 列表已知:
                    // 向 effectiveDriverPath 追加 quotation_line_item_id IN (cld1, cld2, ...) 谓词,
                    // 让视图只返回当前报价单子件自己的工序行，消除历史累积 (236 -> ≤N 行).
                    // 注: 仅 v_composite_child_processes 有 quotation_line_item_id 列 (V207/V209),
                    //     materials / elements / weights 视图没有该列，不能注入 IN 谓词。
                    // 同时保留 quotation_line_item_id IS NULL 的主数据行 (后端视图 UNION 语义).
                    // ANTLR grammar 不原生支持 OR, 改用两段查询合并.
                    String inPath = appendChildLineItemInPredicate(effectiveDriverPath, childLineItemIds);
                    LOG.infof("[COMPOSITE-child expand] path=%s partNo=%s childIds=%d -> IN-filtered path=%s",
                            effectiveDriverPath, partNo, childLineItemIds.size(), inPath);
                    List<Map<String, Object>> childRows =
                        dataLoader.loadByPath(inPath, null, partNo, customerId).get();
                    // 同时查主数据行 (quotation_line_item_id IS NULL 的标准化行)
                    // appendNullLineItemPredicate 返回不带额外谓词的 path (全量行),
                    // 从结果中过滤 quotation_line_item_id == null 的行作为主数据补充.
                    // 注: CpqPathParser 不支持 IS NULL 语法, 只能查全量再内存过滤.
                    String mainDataPath = appendNullLineItemPredicate(effectiveDriverPath);
                    List<Map<String, Object>> allRowsForMainData =
                        dataLoader.loadByPath(mainDataPath, null, partNo, customerId).get();
                    // 过滤出 quotation_line_item_id IS NULL 的主数据行
                    List<Map<String, Object>> mainRows = new ArrayList<>();
                    if (allRowsForMainData != null) {
                        for (var r : allRowsForMainData) {
                            if (r.get("quotation_line_item_id") == null) {
                                mainRows.add(r);
                            }
                        }
                    }
                    // 合并: 子件专属行 + 主数据行 (去重), COMPOSITE 父级视图渲染需要两者
                    java.util.Set<String> childRowKeys = new java.util.HashSet<>();
                    List<Map<String, Object>> merged = new ArrayList<>(childRows != null ? childRows : List.of());
                    if (childRows != null) {
                        for (var r : childRows) {
                            // 用 child_hf_part_no + seq_no 作去重键 (识别同一子件的同一工序)
                            String dedupeKey = String.valueOf(r.get("child_hf_part_no")) + ":"
                                    + String.valueOf(r.get("seq_no"));
                            childRowKeys.add(dedupeKey);
                        }
                    }
                    // 主数据行：仅补充 childRows 没覆盖到的工序 (避免重复)
                    for (var r : mainRows) {
                        String dedupeKey = String.valueOf(r.get("child_hf_part_no")) + ":"
                                + String.valueOf(r.get("seq_no"));
                        if (!childRowKeys.contains(dedupeKey)) {
                            merged.add(r);
                        }
                    }
                    driverRows = merged;
                } else {
                    // lineItemId=null OR (COMPOSITE parent + composite aggregate view, no childLineItemIds) -> full path
                    if (isCompositeAggregateView && isCompositeParent) {
                        LOG.debugf("[COMPOSITE-view expand] path=%s partNo=%s compositeType=%s -> no childLineItemIds, full aggregate",
                                effectiveDriverPath, partNo, compositeType);
                    } else if (lineItemId == null) {
                        LOG.debugf("[expand-driver] lineItemId=null, no lineItem injection for path=%s",
                                effectiveDriverPath);
                    }
                    driverRows = dataLoader.loadByPath(effectiveDriverPath, null, partNo, customerId).get();
                }
            } catch (InterruptedException | ExecutionException e) {
                LOG.warnf("Driver path resolve failed: path=%s, err=%s",
                        effectiveDriverPath, e.getMessage());
                throw new BusinessException("driver 路径查询失败: " + e.getMessage());
            }
            if (driverRows == null) driverRows = List.of();
            resp.rowCount = driverRows.size();

            // 2. �?BASIC_DATA 字段路径列表 + V190 default_source GLOBAL_VARIABLE 解析任务
            List<String> basicDataPaths = parseBasicDataPaths(effectiveFieldsJson);
            List<GvarDefaultTask> gvarTasks = parseGvarDefaultTasks(effectiveFieldsJson);

            // 3. 对每一行逐路径求值 + 全局变量求值（gvar P1-C3: 跨行批量预解析，逐位等价逐行）
            List<Map<String, Object>> gvarPerRow =
                    gvarTasks.isEmpty() ? null : resolveGvarsBatched(gvarTasks, driverRows);
            int _ri = 0;
            for (Map<String, Object> driverRow : driverRows) {
                ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
                row.driverRow = driverRow;
                row.basicDataValues = new LinkedHashMap<>();
                for (String fieldPath : basicDataPaths) {
                    Object value = evaluatePath(fieldPath, driverRow, customerId, partNo);
                    row.basicDataValues.put(fieldPath, value);
                }
                // V190: 把字段 default_source 按本 driver 行解出的 GLOBAL_VARIABLE 值塞入合并 key
                if (gvarPerRow != null) row.basicDataValues.putAll(gvarPerRow.get(_ri));
                resp.rows.add(row);
                _ri++;
            }
        } finally {
            PartVersionContext.clear();
        }

        LOG.infof("[Y1.5 expand-driver] expanded: id=%s code=%s rows=%d partVersion=%s",
                componentId, component.code, resp.rowCount, partVersion);
        // miss 路径成功计算后写入缓存（异常会在上方抛出，不会执行到此处，确保错误不缓存�?
        if (!com.cpq.datasource.sqlview.SqlDebugContext.isActive()) expandCache.put(key, resp);
        return resp;
        } finally {
            com.cpq.datasource.sqlview.SqlViewRuntimeContext.restore(_prevSqlViewCtx);
        }
    }

    /**
     * 批量合桶专用 expand — 一次查多张产品卡片(`partNos`)的同一组件,返回 partNo → 响应。
     *
     * <p>由 `ComponentResource.batchExpand` 的 bucket-merge 调用,bucket 已保证:
     * ① 同 bucket 内 partNos 互不重复;② 该 bucket 的 driver path 不含 `:lineItemId`
     * (否则不能跨卡片合);③ snapshot 命中的 task 已直返,不进合桶。
     *
     * <p>不走 {@code expandCache}(批量结果按 partNo 拆分后写入各自 task 的响应,缓存复用语义复杂),
     * 也不写 lineItemId-tagged cache key。批量场景由上层 batchExpand 一次性调用,缓存收益小。
     *
     * @return Map&lt;partNo, ExpandDriverResponse&gt;。即便某 partNo 视图返 0 行也会返回空响应,
     *         保证调用方对每个 task 都有 result(空 rows / rowCount=0)。
     */
    public Map<String, ExpandDriverResponse> expandMulti(
            UUID componentId, UUID customerId, List<String> partNos,
            Integer partVersion, String overrideDataDriverPath, String overrideFieldsJson) {
        com.cpq.datasource.sqlview.SqlViewRuntimeContext.Snapshot _prev =
                com.cpq.datasource.sqlview.SqlViewRuntimeContext.setNested(componentId, null, null, null);
        try {
            Map<String, ExpandDriverResponse> resultByPart = new java.util.LinkedHashMap<>();
            if (partNos == null || partNos.isEmpty()) return resultByPart;

            Component component = Component.findById(componentId);
            if (component == null) throw new BusinessException(404, "Component not found: " + componentId);

            String effectiveDriverPath = (overrideDataDriverPath != null && !overrideDataDriverPath.isBlank())
                    ? overrideDataDriverPath : component.dataDriverPath;
            String effectiveFieldsJson = (overrideFieldsJson != null && !overrideFieldsJson.isBlank())
                    ? overrideFieldsJson : component.fields;

            // 预生成每个 partNo 的空响应 — 保证 caller 对每个 task 都拿得到 result(即便 0 行)
            for (String pn : partNos) {
                ExpandDriverResponse r = new ExpandDriverResponse();
                r.driverPath = effectiveDriverPath;
                r.rows = new ArrayList<>();
                r.rowCount = 0;
                resultByPart.put(pn, r);
            }

            if (effectiveDriverPath == null || effectiveDriverPath.isBlank()) {
                // 无 driver 路径:走单值 expand 的"虚拟单行"语义,逐 partNo 兜底
                for (String pn : partNos) {
                    resultByPart.put(pn, expand(componentId, customerId, pn, partVersion,
                            overrideDataDriverPath, overrideFieldsJson, null, null, null));
                }
                return resultByPart;
            }

            PartVersionContext.set(partVersion);
            try {
                List<Map<String, Object>> mergedRows;
                try {
                    mergedRows = dataLoader.loadByPath(effectiveDriverPath, null, partNos, customerId).get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new BusinessException("driver 多值路径查询失败: " + e.getMessage());
                }
                if (mergedRows == null) mergedRows = List.of();

                List<String> basicDataPaths = parseBasicDataPaths(effectiveFieldsJson);
                List<GvarDefaultTask> gvarTasks = parseGvarDefaultTasks(effectiveFieldsJson);

                // gvar P1-C3: 跨行批量预解析（按 mergedRows 同序对齐 index），逐位等价逐行
                List<Map<String, Object>> gvarPerRow =
                        gvarTasks.isEmpty() ? null : resolveGvarsBatched(gvarTasks, mergedRows);
                // 按行 hf_part_no 分发回各 partNo 的响应
                for (int mi = 0; mi < mergedRows.size(); mi++) {
                    Map<String, Object> driverRow = mergedRows.get(mi);
                    Object hf = driverRow.get("hf_part_no");
                    String rowPart = hf == null ? null : hf.toString();
                    ExpandDriverResponse target = rowPart == null ? null : resultByPart.get(rowPart);
                    if (target == null) {
                        // 视图返了一行 hf_part_no 不在 partNos 列表里(异常,跳过不报错)
                        continue;
                    }
                    ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
                    row.driverRow = driverRow;
                    row.basicDataValues = new LinkedHashMap<>();
                    for (String fieldPath : basicDataPaths) {
                        Object value = evaluatePath(fieldPath, driverRow, customerId, rowPart);
                        row.basicDataValues.put(fieldPath, value);
                    }
                    if (gvarPerRow != null) row.basicDataValues.putAll(gvarPerRow.get(mi));
                    target.rows.add(row);
                    target.rowCount++;
                }
            } finally {
                PartVersionContext.clear();
            }
            return resultByPart;
        } finally {
            com.cpq.datasource.sqlview.SqlViewRuntimeContext.restore(_prev);
        }
    }

    /**
     * 核价 BOM 递归展开（P1）专用：一次展开<b>单个组件</b>对整个料号闭包 partSet 的全部行，
     * 返回 {@code Map<partNo, ExpandDriverResponse>}（按料号分组，供调用方按 spine 节点 hf_part_no 左关联）。
     *
     * <p>与 {@link #expandMulti}（批量合桶、跨卡片）区别：本入口是<b>单组件单卡</b>调用，<b>不进合桶</b>，
     * 故不会因多卡共享子料号而按 {@code hf_part_no} 拆错（防 AP-37 串卡）。
     *
     * <p>普通 {@code $view}（已确认核价 5 视图均带 {@code hf_part_no} 且<b>无</b> {@code quotation_line_item_id}
     * 维度，V6 customer×material 共享，AP-53）→ 走一次多值 {@code loadByPath}，
     * {@code WHERE hf_part_no = ANY(:hfPartNos)} 外包过滤整棵树。
     * 仅当 path 是 composite 聚合视图（{@code v_composite_child_*}）时回退逐料号单值 {@link #expand}，
     * 保留聚合/IN 谓词语义。
     *
     * @param partSet 闭包料号集合（根 + 全部子孙，去环）
     * @param lineItemId  报价行 id（仅 composite 回退路径透传；普通 V6 视图无此维度，多值入口忽略）
     * @param compositeType 组合类型（仅 composite 回退路径透传）
     */
    public Map<String, ExpandDriverResponse> expandForPartSet(
            UUID componentId, UUID customerId, List<String> partSet,
            UUID lineItemId, String compositeType) {
        Map<String, ExpandDriverResponse> byPart = new LinkedHashMap<>();
        if (partSet == null || partSet.isEmpty()) return byPart;

        Component component = Component.findById(componentId);
        String path = component == null ? null : component.dataDriverPath;
        boolean compositeAgg = path != null
                && (path.contains("v_composite_child_") || path.contains("composite_child_"));

        if (compositeAgg) {
            // composite 聚合视图：逐料号单值 expand，保留 lineItemId / 聚合语义（不合并多值）。
            for (String pn : partSet) {
                byPart.put(pn, expand(componentId, customerId, pn, null, null, null, lineItemId, compositeType));
            }
            LOG.infof("[bom-closure expand] composite-agg per-part fallback comp=%s parts=%d", componentId, partSet.size());
            return byPart;
        }
        // 普通 $view：一次多值 loadByPath，:hfPartNos = ANY(partSet) 外包过滤整棵树。
        Map<String, ExpandDriverResponse> multi = expandMulti(componentId, customerId, partSet, null, null, null);
        LOG.infof("[bom-closure expand] multi-value comp=%s parts=%d rowsBuckets=%d", componentId, partSet.size(), multi.size());
        return multi;
    }

    /** Bucket-merge 用:按 (componentId, driverPath) 缓存视图是否含 :lineItemId 占位符。 */
    private final Map<String, Boolean> viewUsesLineItemIdCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 返回 task 的实际生效 driver path(override 优先,否则取 component.dataDriverPath)。 */
    public String resolveEffectiveDriverPath(UUID componentId, String overrideDataDriverPath) {
        if (overrideDataDriverPath != null && !overrideDataDriverPath.isBlank()) {
            return overrideDataDriverPath;
        }
        Component c = Component.findById(componentId);
        return c == null ? null : c.dataDriverPath;
    }

    /**
     * 该 driver 视图的 sql_template 是否包含 {@code :lineItemId} 占位符 —— 决定能否跨卡片合桶。
     * 含 {@code :lineItemId}(per-quote 工序/组合工艺 mirror)→ 不能合,各 task 单独跑;
     * 不含(纯按 customerCode + hf_part_no 过滤的 mirror)→ 可合。
     * 结果按 (componentId, driverPath) 缓存避免每次反复查表。
     */
    public boolean viewUsesLineItemId(UUID componentId, String driverPath) {
        if (driverPath == null || driverPath.isBlank()) return true;
        String cacheKey = componentId + "::" + driverPath;
        Boolean cached = viewUsesLineItemIdCache.get(cacheKey);
        if (cached != null) return cached;
        String viewName = extractSqlViewName(driverPath);
        if (viewName == null) {
            viewUsesLineItemIdCache.put(cacheKey, true);
            return true; // 非 $view 路径,保守不合
        }
        try {
            com.cpq.component.entity.ComponentSqlView v = com.cpq.component.entity.ComponentSqlView
                    .find("sqlViewName", viewName).firstResult();
            boolean uses = v != null && v.sqlTemplate != null && v.sqlTemplate.contains(":lineItemId");
            viewUsesLineItemIdCache.put(cacheKey, uses);
            return uses;
        } catch (Exception e) {
            return true; // 异常保守不合
        }
    }

    /** 从 driver path 提取视图名(支持 $name 和 $$comp.name 两种形态,去掉尾部 [predicate])。 */
    private static String extractSqlViewName(String driverPath) {
        if (driverPath == null) return null;
        String s = driverPath.trim();
        if (s.startsWith("$$")) {
            int dot = s.indexOf('.', 2);
            if (dot < 0) return null;
            String rest = s.substring(dot + 1);
            int bracket = rest.indexOf('[');
            return bracket >= 0 ? rest.substring(0, bracket) : rest;
        }
        if (s.startsWith("$")) {
            String rest = s.substring(1);
            int bracket = rest.indexOf('[');
            return bracket >= 0 ? rest.substring(0, bracket) : rest;
        }
        return null;
    }

    /**
     * 共享私有 helper（闸门②③④）：该 driver 视图是否「无行维度」——即视图按 customerCode+hf_part_no
     * 过滤、不含 lineItemId / spineKeys / composite 聚合等逐行上下文，可跨行合桶预取。
     *
     * <p>充要条件（全成立）：
     * ② {@code dataDriverPath} 非空且能提取出 $view 名（非 $view 路径保守回落）；
     * ③ path 不含 {@code v_composite_child_} / {@code composite_child_}（composite 聚合视图
     *    走逐 lineItemId 聚合，不能跨行）；
     * ④ 按 <b>componentId 精确取</b> {@code ComponentSqlView}（同名视图跨组件会串号，见记忆
     *    {@code cpq-sqlview-cache-key-needs-component-dim}），其 sql_template 不含
     *    {@code :lineItemId} / {@code quotation_line_item_id} 行维度 / {@link com.cpq.datasource.sqlview.SpineKeysMacro}。
     *
     * <p>任一不满足 → {@code false}（回落逐行 expand，慢但正确）。
     */
    private boolean viewHasNoRowDimension(UUID componentId, String path) {
        if (path == null || path.isBlank()) return false;                    // ② 路径非空
        if (path.contains("v_composite_child_") || path.contains("composite_child_")) return false;  // ③ 非 composite
        String viewName = extractSqlViewName(path);
        if (viewName == null) return false;                                  // ② 必须是 $view 形态
        com.cpq.component.entity.ComponentSqlView v = com.cpq.component.entity.ComponentSqlView
                .find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        if (v == null || v.sqlTemplate == null) return false;               // ④ 视图存在
        String tpl = v.sqlTemplate;
        if (com.cpq.datasource.sqlview.SpineKeysMacro.containsMacro(tpl)) return false;  // ④ 无 :spineKeys
        if (tpl.contains(":lineItemId") || tpl.contains("quotation_line_item_id")) return false;  // ④ 无行维度
        return true;
    }

    /**
     * P2-C4: 该 recursive 核价组件能否纳入「跨行 partSet union 预取」。充要条件(全成立)：
     * ① {@code bom_recursive_expand==true}(否则走 expand 单值,非 union 现场)；
     * ②③④ 视图无行维度（见 {@link #viewHasNoRowDimension}）。
     * 任一不满足 → 回落逐行 {@link #expandForPartSet}(带 li.id / SpineKeysContext),与改动前逐位一致。
     *
     * <p><b>重构等价说明</b>：原实现直接内联闸门②③④；重构后委托 {@link #viewHasNoRowDimension}，
     * 行为与改动前逐位不变（闸门条件完全相同，仅提取为 helper 复用）。
     */
    public boolean eligibleForBomUnion(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if (!Boolean.TRUE.equals(c.bomRecursiveExpand)) return false;     // ①
        return viewHasNoRowDimension(componentId, c.dataDriverPath);      // ②③④
    }

    /**
     * P3-C1: 该报价 driver 组件能否纳入「整单合桶预取」（报价侧 Bug B 安全闸门）。充要条件(全成立)：
     * ① {@code componentType != "EXCEL"}（EXCEL 组件不走 driver expand，统一逐行安全）；
     * ②③④ 视图无行维度（见 {@link #viewHasNoRowDimension}）。
     * 任一不满足 → 回落逐行 expand（保 Bug B + lineItemId 隔离 + composite 聚合语义）。
     *
     * <p>与 {@link #eligibleForBomUnion} 的区别：报价 driver 组件多数
     * {@code bomRecursiveExpand=false}，故去掉闸门①（BOM 递归维度），直接检查视图无行维度。
     *
     * <p><b>精确优于宽松</b>：闸门宁可错挡（慢但正确）不可错放（合桶绕过 lineItem 隔离 → 串号）。
     */
    public boolean eligibleForQuoteBucket(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if ("EXCEL".equals(c.componentType)) return false;               // ① 非 EXCEL
        return viewHasNoRowDimension(componentId, c.dataDriverPath);    // ②③④
    }

    /**
     * Phase 2-2'：核价侧<b>非递归</b> driver 组件能否纳入「整单 partNo 合桶」。充要条件(全成立)：
     * ① {@code bomRecursiveExpand != true}（递归组件走 {@link #eligibleForBomUnion} 的 partSet union，不重复处理）；
     * ② {@code componentType != "EXCEL"}；
     * ③④⑤ 视图无行维度（见 {@link #viewHasNoRowDimension}：无 lineItemId / 无 spineKeys / 非 composite）。
     *
     * <p>满足时该组件在 {@code expandTemplateDriverBaseRows} 的 recursive=false 分支可用
     * {@code expandMulti(全单 distinct 根料号)} 一次取回（170→1/组件），按 partNo 回配；
     * 与逐行 {@code expand(…,partNo,…,li.id,…)} 对无行维度视图逐位等价（li.id 被视图忽略）。
     * 任一不满足 → 回落逐行 expand（慢但正确）。
     */
    public boolean eligibleForNonRecursiveCostingBucket(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if (Boolean.TRUE.equals(c.bomRecursiveExpand)) return false;     // ① 非递归
        if ("EXCEL".equals(c.componentType)) return false;               // ②
        return viewHasNoRowDimension(componentId, c.dataDriverPath);    // ③④⑤
    }

    /**
     * Phase 2-2''(#3):核价侧<b>非递归 + 仅 spineKeys 维度</b>组件能否纳入「整单 spineKeys 并集合桶」。
     * ① 非递归;② 非 EXCEL;③ 非 composite;④ 视图<b>含 :spineKeys 宏</b>;⑤ 视图<b>不含 lineItemId 维度</b>。
     *
     * <p>满足者 + 运行时全单 {@code maxTriplesPerPart==1}(spine 平)时,可设「全单三元组并集」SpineKeysContext
     * 后一次 {@code expandForPartSet(全单料号)},按 partNo 回配,与逐行(每行 1 三元组上下文)逐位等价;
     * {@code maxTriples>1}(真多节点 BOM 树)→ 回落逐行(调用方据此不合桶)。
     */
    public boolean eligibleForSpineKeysFlatBucket(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if (Boolean.TRUE.equals(c.bomRecursiveExpand)) return false;     // ① 非递归
        if ("EXCEL".equals(c.componentType)) return false;               // ②
        String path = c.dataDriverPath;
        if (path == null || path.isBlank()) return false;
        if (path.contains("v_composite_child_") || path.contains("composite_child_")) return false; // ③ 非 composite
        String viewName = extractSqlViewName(path);
        if (viewName == null) return false;
        com.cpq.component.entity.ComponentSqlView v = com.cpq.component.entity.ComponentSqlView
                .find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        if (v == null || v.sqlTemplate == null) return false;
        String tpl = v.sqlTemplate;
        if (!com.cpq.datasource.sqlview.SpineKeysMacro.containsMacro(tpl)) return false;  // ④ 必须有 spineKeys
        if (tpl.contains(":lineItemId") || tpl.contains("quotation_line_item_id")) return false;  // ⑤ 无 lineItemId
        return true;
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** V190: default_source GLOBAL_VARIABLE 任务 �?code + 动�?key 映射（包级可见：供 P1-C3 批量等价测试构造） */
    static class GvarDefaultTask {
        final String code;
        final Map<String, String> keyFieldRefs;  // key 列名 �?driver 行字段名
        GvarDefaultTask(String code, Map<String, String> keyFieldRefs) {
            this.code = code; this.keyFieldRefs = keyFieldRefs;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GvarDefaultTask> parseGvarDefaultTasks(String fieldsJson) {
        List<GvarDefaultTask> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                // 路径 A: INPUT_NUMBER/TEXT �?default_source.GLOBAL_VARIABLE
                collectGvarTask(f.get("default_source"), out, "code", "key_field_refs");
                // 路径 B (hotfix): DATA_SOURCE �?datasource_binding.GLOBAL_VARIABLE
                collectGvarTask(f.get("datasource_binding"), out, "global_variable_code", "key_field_refs");
                // 路径 C (2026-05-22 fix): BASIC_DATA 字段顶层 global_variable_code
                // 绕开 ImplicitJoinRewriter.tableColumnsCache 缓存失效导致谓词漏注入返多行数组的问题.
                // gvar task 直查 GlobalVariableService (KV_TABLE / COSTING_VIEW) 获取单行精确值,
                // 结果写入 basicDataValues["@gvar:CODE"], 供前端 BASIC_DATA 渲染 fallback 链使用.
                // key_field_refs 为空 Map -> resolveGvarForRow 走 def.keyColumns 同名默认映射
                // (driver row 里的列名与 GV keyColumns 一致, 如 element_code -> element_code).
                if ("BASIC_DATA".equals(String.valueOf(f.get("field_type")))) {
                    Object gvcObj = f.get("global_variable_code");
                    if (gvcObj != null) {
                        String gvc = gvcObj.toString().trim();
                        if (!gvc.isBlank() && !"null".equals(gvc)) {
                            // keyFieldRefs 留空: resolveGvarForRow 按 def.keyColumns 做同名映射
                            if (out.stream().noneMatch(t -> t.code.equals(gvc))) {
                                out.add(new GvarDefaultTask(gvc, new LinkedHashMap<>()));
                                LOG.debugf("[parseGvarDefaultTasks] BASIC_DATA gvar task added: code=%s", gvc);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("parse default_source from fields failed: %s", e.getMessage());
        }
        return out;
    }

    /** �?default_source / datasource_binding map 中抽�?GLOBAL_VARIABLE 任务. dedupe by code. */
    @SuppressWarnings("unchecked")
    private static void collectGvarTask(Object configObj, List<GvarDefaultTask> out,
                                        String codeField, String refsField) {
        if (!(configObj instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) configObj;
        if (!"GLOBAL_VARIABLE".equals(String.valueOf(m.get("type")))) return;
        Object codeObj = m.get(codeField);
        if (codeObj == null) return;
        String code = codeObj.toString();
        if (code.isBlank() || "null".equals(code)) return;
        Map<String, String> refs = new LinkedHashMap<>();
        Object refsObj = m.get(refsField);
        if (refsObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) refsObj).entrySet()) {
                refs.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        if (out.stream().noneMatch(t -> t.code.equals(code))) {
            out.add(new GvarDefaultTask(code, refs));
        }
    }

    /**
     * V190: 组装某 driver row 对某 gvar task 的 keyValues（key_field_refs 名映射 def.keyColumns → driver row 字段，
     * 含 *_code/*_name 别名兜底）。任一 key 解不出 → 返 null（= 该行该 gvar 不解，值应为 null）。
     * 由 {@link #resolveGvarForRow}（逐行）与 {@link #resolveGvarsBatched}（批量）共用，保证两路组装逻辑一致。
     */
    Map<String, Object> assembleGvarKeyValues(GvarDefaultTask task, Map<String, Object> driverRow) {
        GlobalVariableDefinition def = globalVariableService.getByCode(task.code).orElse(null);
        if (def == null || !def.isLookup()) return null;
        Map<String, Object> keyValues = new LinkedHashMap<>();
        for (String col : def.keyColumns) {
            // 优先按 key_field_refs[col] 取 driver 行字段，默认同名映射 col → driverRow[col]
            String driverField = task.keyFieldRefs.getOrDefault(col, col);
            Object v = driverRow.get(driverField);
            // 2026-05-22 fix (情况B): driverRow 列名与 GV keyColumns 不一致时（如
            // v_composite_child_elements.element_name vs ELEM_PRICE.keyColumns=["element_code"]），
            // 同名映射取出 null，探测候选别名列（*_code <-> *_name 互换等）兜底。
            if (v == null) v = findAliasValue(driverField, driverRow);
            if (v == null) return null;  // 任一 key 空 → 不解
            keyValues.put(col, v);
        }
        return keyValues;
    }

    /** V190: 从 driver row 按 GLOBAL_VARIABLE 单行精确取值（逐行；批量路径见 {@link #resolveGvarsBatched}）。 */
    Object resolveGvarForRow(GvarDefaultTask task, Map<String, Object> driverRow) {
        try {
            Map<String, Object> keyValues = assembleGvarKeyValues(task, driverRow);
            if (keyValues == null) return null;
            return globalVariableService.resolveValue(task.code, keyValues);
        } catch (Exception e) {
            LOG.warnf("resolveGvarForRow failed for code=%s: %s", task.code, e.getMessage());
            return null;
        }
    }

    /**
     * P1-C3: 批量预解析 gvar —— 每 task 一次 IN 查（KvTable，跨行折叠 N 次单查为 1 次）。
     * 返回与 {@code driverRows} <b>同序</b> 的 per-row {@code gvarKey→value} map 列表，与逐行
     * {@link #resolveGvarForRow} <b>逐位等价</b>：组装逻辑共用 {@link #assembleGvarKeyValues}，
     * 批量值由 {@link GlobalVariableService#resolveValues} 提供（KvTable 单列 IN，与 resolveValue 逐位一致；
     * SCALAR/View 服务层自动逐行回落）。任一 task 批量异常 → 该 task 整体逐行回落老逻辑（绝不改结果）。
     */
    List<Map<String, Object>> resolveGvarsBatched(List<GvarDefaultTask> tasks,
                                                  List<Map<String, Object>> driverRows) {
        List<Map<String, Object>> perRow = new ArrayList<>(driverRows.size());
        for (int i = 0; i < driverRows.size(); i++) perRow.add(new LinkedHashMap<>());
        for (GvarDefaultTask task : tasks) {
            // 1) 每行组装 keyValues（与逐行同逻辑；null=该行不解）
            List<Map<String, Object>> kvs = new ArrayList<>(driverRows.size());
            for (Map<String, Object> dr : driverRows) {
                Map<String, Object> kv;
                try { kv = assembleGvarKeyValues(task, dr); } catch (Exception e) { kv = null; }
                kvs.add(kv);
            }
            // 2) 非 null 行批量解析；null 行直接 null
            List<Integer> idx = new ArrayList<>();
            List<Map<String, Object>> nonNull = new ArrayList<>();
            for (int i = 0; i < kvs.size(); i++) if (kvs.get(i) != null) { idx.add(i); nonNull.add(kvs.get(i)); }
            try {
                List<java.math.BigDecimal> vals = nonNull.isEmpty()
                        ? List.of() : globalVariableService.resolveValues(task.code, nonNull);
                for (int j = 0; j < idx.size(); j++) perRow.get(idx.get(j)).put(gvarKey(task.code), vals.get(j));
                for (int i = 0; i < kvs.size(); i++) if (kvs.get(i) == null) perRow.get(i).put(gvarKey(task.code), null);
            } catch (Exception e) {
                // 批量失败 → 该 task 整体逐行回落（保守，等价老逻辑）
                LOG.warnf("resolveGvarsBatched fallback per-row for code=%s: %s", task.code, e.getMessage());
                for (int i = 0; i < driverRows.size(); i++)
                    perRow.get(i).put(gvarKey(task.code), resolveGvarForRow(task, driverRows.get(i)));
            }
        }
        return perRow;
    }

    /**
     * 2026-05-22: 当 GV keyColumns 指定的列名在 driverRow 中不存在时，
     * 探测候选别名列，返回第一个非 null 的值（或 null）。
     *
     * 规则：
     *   1. col 以 "_code" 结尾 → 尝试同前缀的 "_name" 变体（element_code → element_name）
     *   2. col 以 "_name" 结尾 → 尝试同前缀的 "_code" 变体（element_name → element_code）
     *   3. 通用兜底：遍历 driverRow 找第一个列名包含公共前缀（col 去掉后缀）的非 null 值
     *
     * 此方法只探测值，不改变 keyValues 的键名（对 GV resolver 无影响）。
     */
    private static Object findAliasValue(String col, Map<String, Object> driverRow) {
        // 规则1: *_code -> *_name
        if (col.endsWith("_code")) {
            String nameVariant = col.substring(0, col.length() - 5) + "_name";
            Object v = driverRow.get(nameVariant);
            if (v != null) return v;
        }
        // 规则2: *_name -> *_code
        if (col.endsWith("_name")) {
            String codeVariant = col.substring(0, col.length() - 5) + "_code";
            Object v = driverRow.get(codeVariant);
            if (v != null) return v;
        }
        // 规则3: 通用前缀探测（col 去掉最后一段 "_xxx"，找包含此前缀的 driverRow 列）
        if (col.contains("_")) {
            String prefix = col.substring(0, col.lastIndexOf('_'));
            for (Map.Entry<String, Object> entry : driverRow.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static void addPathIfPresent(List<String> out, Object pathObj) {
        if (pathObj == null) return;
        String path = String.valueOf(pathObj).trim();
        if (path.isEmpty()) return;
        if (!path.startsWith("{")) path = "{" + path + "}";
        if (!out.contains(path)) out.add(path);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseBasicDataPaths(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                // BASIC_DATA 字段: �?basic_data_path
                if ("BASIC_DATA".equals(String.valueOf(f.get("field_type")))) {
                    addPathIfPresent(out, f.get("basic_data_path"));
                }
                // V184: 任意字段(典型 INPUT_NUMBER)�?default_basic_data_path �?
                // 用户行值为空时回退到该路径取全局变量默认�?
                addPathIfPresent(out, f.get("default_basic_data_path"));
                // V190 default_source.BNF_PATH (典型 INPUT_NUMBER 兜底�?BNF 路径)
                Object ds = f.get("default_source");
                if (ds instanceof Map<?, ?> dsMap) {
                    String dsType = String.valueOf(dsMap.get("type"));
                    // BNF_PATH / BASIC_DATA 都把 path 纳入逐行求值。BASIC_DATA 的 $view 整行已在
                    // 无-driver 虚拟行分支 merge 进 driverRow → evaluatePath 短路按中文列名取值。
                    if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                        addPathIfPresent(out, dsMap.get("path"));
                    }
                }
                // Phase J: DATA_SOURCE.BNF_PATH 子类�?�?datasource_binding.bnf_path
                // 没在这里采集会导�?batch-expand 返的 basicDataValues 不含该路径键,
                // 前端 DATA_SOURCE 渲染分支永久 "加载�? (AP-31 协议传播�?
                if ("DATA_SOURCE".equals(String.valueOf(f.get("field_type")))) {
                    Object binding = f.get("datasource_binding");
                    if (binding instanceof Map<?, ?> bMap) {
                        if ("BNF_PATH".equals(String.valueOf(bMap.get("type")))) {
                            addPathIfPresent(out, bMap.get("bnf_path"));
                        }
                    }
                }
                // 2026-05-20: LIST_FORMULA 字段 list_formula_config 内的 BNF path 引用 �?
                // 公式形如 "{mat_bom.composition_pct} * 5" �? basicDataValues 必须�?{mat_bom.composition_pct}.
                // 否则前端 evaluateListFormulaString 查不到值就�?0, 公式求值错�?
                if ("LIST_FORMULA".equals(String.valueOf(f.get("field_type")))) {
                    Object lfc = f.get("list_formula_config");
                    if (lfc instanceof Map<?, ?> lfcMap) {
                        Object rules = lfcMap.get("per_item_rules");
                        if (rules instanceof Map<?, ?> rulesMap) {
                            for (Object ruleObj : rulesMap.values()) {
                                if (!(ruleObj instanceof Map<?, ?> ruleMap)) continue;
                                // branches[i].formula �?{path} 路径
                                Object branches = ruleMap.get("branches");
                                if (branches instanceof List<?> brList) {
                                    for (Object br : brList) {
                                        if (br instanceof Map<?, ?> brMap) {
                                            collectBnfPathsFromFormula(out, brMap.get("formula"));
                                            // 2026-05-20 (C): 双轨 formula_composite path 也采�?
                                            // �?COMPOSITE 视角下的 expand-driver 能预解析对应路径.
                                            collectBnfPathsFromFormula(out, brMap.get("formula_composite"));
                                        }
                                    }
                                }
                                // default_formula 同样�?
                                collectBnfPathsFromFormula(out, ruleMap.get("default_formula"));
                                collectBnfPathsFromFormula(out, ruleMap.get("default_formula_composite"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("parse component.fields failed: %s", e.getMessage());
        }
        return out;
    }

    /** 从 "$cp_view.品名" / "{$cp_view.品名}" 提取视图基路径 "$cp_view"(去花括号 + 去叶列, 保留谓词);非 $ 视图返 null。 */
    private static String viewBasePath(String fullPath) {
        if (fullPath == null) return null;
        String s = fullPath.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        if (!s.startsWith("$")) return null;
        int depth = 0, lastDot = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == '.' && depth == 0) lastDot = i;
        }
        return lastDot < 0 ? s : s.substring(0, lastDot);
    }

    /** 收集 default_source.type=BASIC_DATA 字段引用的去重 $view 基路径(用于无-driver 虚拟行整行 merge)。 */
    @SuppressWarnings("unchecked")
    private static List<String> parseBasicDataDefaultViewBases(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                Object ds = f.get("default_source");
                if (!(ds instanceof Map<?, ?> dsMap)) continue;
                if (!"BASIC_DATA".equals(String.valueOf(dsMap.get("type")))) continue;
                Object pathObj = dsMap.get("path");
                if (pathObj == null) continue;
                String base = viewBasePath(String.valueOf(pathObj));
                if (base != null && !out.contains(base)) out.add(base);
            }
        } catch (Exception e) {
            LOG.warnf("parse BASIC_DATA default view bases failed: %s", e.getMessage());
        }
        return out;
    }

    /**
     * 2026-05-20: �?LIST_FORMULA 公式字符串内 {table.col} 形态的 BNF path token 加入路径采集.
     * �?`.` �?{...} 视为 BNF path; 不含 `.` 视为全局变量 code (不在此采�?.
     */
    private static void collectBnfPathsFromFormula(List<String> out, Object formulaObj) {
        if (formulaObj == null) return;
        String formula = String.valueOf(formulaObj);
        if (formula.isBlank()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}]+)\\}").matcher(formula);
        while (m.find()) {
            String token = m.group(1).trim();
            if (token.contains(".")) {
                addPathIfPresent(out, token);  // addPathIfPresent 自动�?{}
            }
            // 不含 . = 全局变量 code, 不采�?
        }
    }

    private Object evaluatePath(String fieldPath, Map<String, Object> driverRow,
                                 UUID customerId, String partNo) {
        // 短路：当 basic_data_path 的叶字段已经存在�?driverRow（典型的"自表"场景�?
        // driver_path �?basic_data_path 查同一张表，例�?
        // driver=mat_fee[fee_type IN (..)]、field=mat_fee[..]·dim_element_name），
        // 直接�?driver 行取值即可——无需再下发一个被 ImplicitJoinRewriter 注入
        // 整行列条件、最终被自己 = 自己 收窄到无意义、又�?IN 多类型时夹击错配�?SQL�?
        // 风险面：如果 driver �?target 是不同表但恰好同名列，会"早走"读到 driver
        // 的字段而不�?target 的。CPQ 内列命名按语义统一，这种冲突极少；遇到再加白名单�?
        // 业务规则 (2026-05-26 AP-53)：BNF path 查询只能用组件配置的 SQL 视图。
        // 实现路径: data_driver_path 也改为 $<mirror> 引用 (DataLoader 内部转 SqlViewExecutor.executeAllRows)。
        // driverRow 来源就是 mirror SQL 执行结果 — 字段层从 driverRow 短路取列等价于从 mirror 取列。
        // 短路保留是性能优化（避免每字段重跑 N 次 SQL），不破坏 mirror-only 数据真相源约束。
        String leafField = extractLeafField(fieldPath);
        if (leafField != null && driverRow != null && driverRow.containsKey(leafField)) {
            return driverRow.get(leafField);
        }

        EvaluationContext.Builder builder = EvaluationContext.builder()
                .dataLoader(dataLoader)
                .driverRow(driverRow);
        if (customerId != null) builder.customerId(customerId);
        if (partNo != null && !partNo.isBlank()) builder.partNo(partNo);

        Object result = formulaEngine.evaluate(fieldPath, builder.build());
        if (result instanceof FormulaError fe) {
            return fe.toString();
        }
        return result;
    }

    /**
     * �?BASIC_DATA 路径里提取末段字段名�?
     *  支持形态：
     *    {table[pred].field}  �?field
     *    table.field          �?field
     *    {table.field}        �?field
     *    table[pred1].sub[pred2].field �?field
     */
    private static String extractLeafField(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) return null;
        String s = fieldPath.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        // 找最后一个不在方括号 / 圆括号内�?'.'
        int depth = 0;
        int lastDot = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == '.' && depth == 0) lastDot = i;
        }
        if (lastDot < 0) return null;
        String tail = s.substring(lastDot + 1).trim();
        // 排除带方括号的段（说明叶子也是表引用，不是简单字段）
        if (tail.contains("[") || tail.isEmpty()) return null;
        return tail;
    }

    /**
     * 行键候选计算（纯逻辑，不连 DB，供单测直接调用）。
     * 对每个字段用 basic_data_path 反查 driver 真实列名(leaf)，并用 driverColumns 交叉校验是否可作行键。
     *
     * @param dataDriverPath 组件 driver 路径（仅用于 reason 文案判断，可为 $视图 或其他）
     * @param fields         前端编辑态字段列表（loose map：name / field_type / basic_data_path）
     * @param driverColumns  driver 视图的真实列名集合（来自 ComponentSqlView.declaredColumns）；空集表示无列信息
     */
    public static List<com.cpq.component.dto.RowKeyCandidatesResponse.Candidate> resolveRowKeyCandidates(
            String dataDriverPath,
            List<Map<String, Object>> fields,
            Set<String> driverColumns) {
        List<com.cpq.component.dto.RowKeyCandidatesResponse.Candidate> out = new ArrayList<>();
        boolean haveColumns = driverColumns != null && !driverColumns.isEmpty();
        if (fields == null) return out;
        for (Map<String, Object> f : fields) {
            var c = new com.cpq.component.dto.RowKeyCandidatesResponse.Candidate();
            c.fieldName = f.get("name") == null ? null : String.valueOf(f.get("name"));
            c.displayName = c.fieldName;
            String fieldType = f.get("field_type") == null ? "" : String.valueOf(f.get("field_type"));

            Object pathObj = f.get("basic_data_path");
            String basicPath = pathObj == null ? null : String.valueOf(pathObj);
            boolean hasBasicPath = basicPath != null && !basicPath.isBlank();

            // 输入字段分支（无 driver 列也可作行键，取手填值）
            if (!hasBasicPath && ("INPUT_TEXT".equals(fieldType) || "INPUT_NUMBER".equals(fieldType))) {
                if (haveColumns && c.fieldName != null && driverColumns.contains(c.fieldName)) {
                    c.eligible = false;
                    c.reason = "字段名与 driver 列撞名，不能作行键";
                } else {
                    c.eligible = true;
                    c.resolvedColumn = c.fieldName;
                    c.source = "input";
                    c.reason = null;
                }
                out.add(c);
                continue;
            }

            if (!hasBasicPath) {
                c.eligible = false;
                c.reason = "该字段无 driver 列，不能作行键";
                out.add(c);
                continue;
            }
            String leaf = extractLeafField(basicPath);
            if (leaf == null) {
                c.eligible = false;
                c.reason = "该字段无 driver 列，不能作行键";
                out.add(c);
                continue;
            }
            c.resolvedColumn = leaf;
            if (!haveColumns) {
                c.eligible = false;
                c.reason = "该 driver 无列信息，请先将 driver 配为 SQL 视图";
            } else if (driverColumns.contains(leaf)) {
                c.eligible = true;
                c.source = "driver";
                c.reason = null;
            } else {
                c.eligible = false;
                c.reason = "该字段不取自 driver 行，不能作行键";
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 行键候选（带 DB 取数）：从 dataDriverPath 解析 $视图名，查 ComponentSqlView.declaredColumns
     * 得 driver 真实列名集合，再委托 {@link #resolveRowKeyCandidates}。
     */
    public List<com.cpq.component.dto.RowKeyCandidatesResponse.Candidate> computeRowKeyCandidates(
            UUID componentId, String dataDriverPath, List<Map<String, Object>> fields) {
        java.util.Set<String> cols = loadDriverColumnNames(componentId, dataDriverPath);
        return resolveRowKeyCandidates(dataDriverPath, fields, cols);
    }

    /** 取 driver $视图的列名集合；非 $视图 / 未保存 / 无列 → 空集。 */
    private java.util.Set<String> loadDriverColumnNames(UUID componentId, String dataDriverPath) {
        String viewName = extractSqlViewName(dataDriverPath);
        if (viewName == null) return java.util.Set.of();
        com.cpq.component.entity.ComponentSqlView v =
                com.cpq.component.entity.ComponentSqlView
                    .find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        if (v == null) {
            v = com.cpq.component.entity.ComponentSqlView.find("sqlViewName", viewName).firstResult();
        }
        if (v == null || v.declaredColumns == null || v.declaredColumns.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(v.declaredColumns);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    String nm = n.path("name").asText(null);
                    if (nm != null && !nm.isBlank()) names.add(nm);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[row-key-candidates] declaredColumns 解析失败 view=%s: %s", viewName, e.getMessage());
        }
        return names;
    }

    /**
     * 为 COMPOSITE 父级聚合视图路径追加 quotation_line_item_id IN (...) 谓词。
     *
     * <p>输入: "v_composite_child_processes"
     * <p>输出: "v_composite_child_processes[quotation_line_item_id IN ('id1','id2')]"
     *
     * <p>若 path 已含方括号谓词，则在首段现有谓词中追加 AND。
     * <p>这允许 CpqPathParser / SqlCompiler 正确解析 IN 谓词并生成 SQL WHERE IN (?,?,...) 子句。
     */
    static String appendChildLineItemInPredicate(String driverPath, List<UUID> childLineItemIds) {
        if (driverPath == null || driverPath.isBlank() || childLineItemIds == null || childLineItemIds.isEmpty()) {
            return driverPath;
        }
        // 构造 IN 列表字符串: ('id1','id2',...)
        StringBuilder inList = new StringBuilder("(");
        for (int i = 0; i < childLineItemIds.size(); i++) {
            if (i > 0) inList.append(",");
            inList.append("'").append(childLineItemIds.get(i).toString()).append("'");
        }
        inList.append(")");
        String inPredicate = "quotation_line_item_id IN " + inList;

        // 剥去花括号 (如果有)
        String path = DataLoader.normalizePath(driverPath);
        // 查找首段末尾是否已有方括号谓词
        int firstBracket = path.indexOf('[');
        int dotPos = path.indexOf('.');
        // 首段之后的位置 (有 . 则到第一个 . 之前; 否则到末尾)
        int firstSegEnd = (dotPos >= 0) ? dotPos : path.length();

        if (firstBracket >= 0 && firstBracket < firstSegEnd) {
            // 已有谓词: 在 ] 前插入 AND <inPredicate>
            // 找对应 ] 位置
            int depth = 0;
            int closeIdx = -1;
            for (int i = firstBracket; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { closeIdx = i; break; } }
            }
            if (closeIdx < 0) {
                // 不平衡括号 — 退化到追加新谓词
                return path + "[" + inPredicate + "]";
            }
            return path.substring(0, closeIdx) + " AND " + inPredicate + path.substring(closeIdx);
        } else {
            // 无谓词: 在首段名后插入新方括号谓词
            return path.substring(0, firstSegEnd) + "[" + inPredicate + "]" + path.substring(firstSegEnd);
        }
    }

    /**
     * 为视图路径追加 quotation_line_item_id IS NULL 谓词，用于查主数据行。
     *
     * <p>注意: CpqPathParser ANTLR grammar 不支持 IS NULL 语法 (仅支持等值/IN/LIKE)。
     * 改用等值哨兵方式: 实际主数据行 quotation_line_item_id 真的是 NULL，无法通过等值查询。
     * 因此本方法实际返回 driverPath 本身 (不追加任何谓词)，
     * 让 DataLoader 返回视图全量行，再由调用方过滤 quotation_line_item_id == null 的行。
     *
     * <p>这避免了在此引入 raw JDBC / SQL 字符串拼接的复杂性。
     * 调用方 (expand COMPOSITE 分支) 从 mainRows 中只取 quotation_line_item_id 为 null 的行。
     */
    static String appendNullLineItemPredicate(String driverPath) {
        // 直接返回不带额外谓词的路径;
        // 调用方拿到全量行后按 quotation_line_item_id==null 过滤主数据行
        return DataLoader.normalizePath(driverPath);
    }
}
