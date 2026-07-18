package com.cpq.costing.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.ComparisonConfigDTO;
import com.cpq.costing.dto.ComparisonDataDTO;
import com.cpq.costing.dto.ComparisonMetaDTO;
import com.cpq.costing.entity.QuotationComparisonConfig;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.QuotationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-0717 报价单比对视图 — 服务层。
 *
 * <p><b>单一数据源纪律（AC-3）</b>：{@link #getData} 的 productTotal/tabTotal/subtotals 一律来自
 * {@code CardSnapshotService} 已经算好并落库的 {@code quote_card_values}/{@code costing_card_values}
 * JSON（{@code tabs[].subtotal} / {@code tabs[].subtotalByColumn}，由
 * {@code CardSnapshotService#assembleTabsWithFormulaResults}/{@code #buildTabNode}/
 * {@code #backfillSubtotalsFromResolved} 写入 —— 与 {@code CostingSubtotalUtil#extractUnitSubtotal}
 * 读同一批字段、同一算法）。本类只负责两件事：
 * <ol>
 *   <li>选源：{@code frozen=false} 走 {@link CardSnapshotService#ensureCardValues} 懒算补齐后调
 *       {@link QuotationService#getById}（与编辑态 Tab 同源）；{@code frozen=true} 读
 *       {@link CostingOrder#frozenDto}（提交时序列化的 QuotationDTO）+ {@code costingRender} 覆盖
 *       核价侧（已应用版本 override 的渲染缓存），镜像前端 {@code CostingReviewPage.buildFrozenView}
 *       同一降级算法（只读，不重算）。</li>
 *   <li>解析：从卡片值 JSON 里读已经算好的 {@code subtotal}/{@code subtotalByColumn} 字段。</li>
 * </ol>
 * 全程不调用 FormulaCalculator/ComponentDataEffectiveRows 重新求值，不新写任何取值 SQL/公式（AP-50）。
 *
 * <p>精度：按 api.md §0.3，后端返回 JSON 里已有的原始数值（不额外 setScale/预格式化），
 * 4 位/2 位展示口径由前端负责。
 */
@ApplicationScoped
public class ComparisonViewService {

    private static final Logger LOG = Logger.getLogger(ComparisonViewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_BUCKETS = Set.of("SALES", "FINANCE");
    private static final Set<String> VALID_KINDS = Set.of("PRODUCT_TOTAL", "TAB_PAIR");
    private static final String TAB_TOTAL_KEY = "__TAB_TOTAL__";

    @Inject
    QuotationService quotationService;

    @Inject
    CardSnapshotService cardSnapshotService;

    // ───────────────────────── meta ─────────────────────────

    /** 两侧「页签 → 可比对值」目录（api.md §1）。与 bucket 无关。 */
    public ComparisonMetaDTO getMeta(UUID quotationId) {
        // 结构快照尽力而为补建（同 QuotationResource.saveDraft 的幂等纪律），保证
        // quoteCardStructure/costingCardStructure 已就绪；已存在则 no-op。
        try {
            cardSnapshotService.ensureStructure(quotationId);
        } catch (Exception ignore) {
            // 结构快照尽力而为，不阻断 meta 目录（对齐 QuotationResource.saveDraft 同款容错）
        }

        QuotationDTO dto = quotationService.getById(quotationId); // 报价单不存在时抛 404 BusinessException

        ComparisonMetaDTO meta = new ComparisonMetaDTO();
        meta.quoteTabs = buildTabMetas(dto.quoteCardStructure);
        meta.costingTabs = buildTabMetas(dto.costingCardStructure);
        return meta;
    }

    private List<ComparisonMetaDTO.TabMeta> buildTabMetas(JsonNode structure) {
        List<ComparisonMetaDTO.TabMeta> out = new ArrayList<>();
        if (structure == null) return out;
        JsonNode tabs = structure.path("tabs");
        if (!tabs.isArray()) return out;

        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            if (cid.isBlank()) continue;

            ComparisonMetaDTO.TabMeta tm = new ComparisonMetaDTO.TabMeta();
            tm.componentId = cid;
            tm.tabName = tab.path("tabName").asText("");
            tm.sortOrder = tab.path("sortOrder").asInt(0);

            List<ComparisonMetaDTO.MetricMeta> metrics = new ArrayList<>();
            for (JsonNode f : tab.path("fields")) {
                if (!f.path("isSubtotal").asBoolean(false)) continue;
                String key = f.path("name").asText("");
                if (key.isBlank()) continue;
                ComparisonMetaDTO.MetricMeta m = new ComparisonMetaDTO.MetricMeta();
                m.key = key;
                m.label = f.path("label").asText(key);
                m.type = "SUBTOTAL_FIELD";
                metrics.add(m);
            }
            ComparisonMetaDTO.MetricMeta total = new ComparisonMetaDTO.MetricMeta();
            total.key = TAB_TOTAL_KEY;
            total.label = "页签合计";
            total.type = "TAB_TOTAL";
            metrics.add(total);

            tm.metrics = metrics;
            out.add(tm);
        }
        out.sort(Comparator.comparingInt(t -> t.sortOrder == null ? 0 : t.sortOrder));
        return out;
    }

    // ───────────────────────── config ─────────────────────────

    /** 读该（报价单 × 桶）比对列配置；从未保存过 → columns=null（api.md §3）。 */
    public ComparisonConfigDTO getConfig(UUID quotationId, String bucket) {
        validateBucket(bucket);
        requireQuotation(quotationId);

        QuotationComparisonConfig cfg = QuotationComparisonConfig.findByQuotationAndBucket(quotationId, bucket);
        ComparisonConfigDTO dto = new ComparisonConfigDTO();
        dto.quotationId = quotationId;
        dto.bucket = bucket;
        if (cfg == null) {
            dto.columns = null;
        } else {
            dto.columns = parseColumnsOrNull(cfg.columns);
            dto.updatedAt = cfg.updatedAt;
        }
        return dto;
    }

    /** 全量覆盖 upsert 该（报价单 × 桶）比对列配置（api.md §4）。 */
    @Transactional
    public ComparisonConfigDTO upsertConfig(UUID quotationId, String bucket, JsonNode columns) {
        validateBucket(bucket);
        requireQuotation(quotationId);
        validateColumns(columns);

        String columnsJson;
        try {
            columnsJson = MAPPER.writeValueAsString(columns);
        } catch (Exception e) {
            throw new BusinessException(400, "columns 序列化失败: " + e.getMessage());
        }

        QuotationComparisonConfig cfg = QuotationComparisonConfig.findByQuotationAndBucket(quotationId, bucket);
        if (cfg == null) {
            cfg = new QuotationComparisonConfig();
            cfg.quotationId = quotationId;
            cfg.bucket = bucket;
            cfg.columns = columnsJson;
            cfg.persist();
        } else {
            cfg.columns = columnsJson; // 全量覆盖，非增量语义（api.md §4）
        }

        ComparisonConfigDTO dto = new ComparisonConfigDTO();
        dto.quotationId = quotationId;
        dto.bucket = bucket;
        dto.columns = columns;
        dto.updatedAt = cfg.updatedAt;
        return dto;
    }

    private void validateBucket(String bucket) {
        if (bucket == null || !VALID_BUCKETS.contains(bucket)) {
            throw new BusinessException(400, "bucket 必须为 SALES 或 FINANCE");
        }
    }

    private void requireQuotation(UUID quotationId) {
        if (quotationId == null || Quotation.findById(quotationId) == null) {
            throw new BusinessException(404, "报价单不存在: " + quotationId);
        }
    }

    /**
     * 轻量结构校验（backtask.md T2）：必须是数组；每项含 id（非空）/kind（合法枚举）/threshold（数字）；
     * kind=TAB_PAIR 时 quoteComponentId/quoteMetric/costingComponentId/costingMetric 四键必填（api.md §5.2）。
     * 不校验 componentId/metric 是否仍存在于当前模板（那是前端读取时对照 meta 处理的职责，api.md §4）。
     */
    private void validateColumns(JsonNode columns) {
        if (columns == null || !columns.isArray()) {
            throw new BusinessException(400, "columns 必须是数组");
        }
        int idx = 0;
        for (JsonNode col : columns) {
            String where = "columns[" + idx + "]";
            if (!col.isObject()) throw new BusinessException(400, where + " 必须是对象");

            String id = col.path("id").asText(null);
            if (id == null || id.isBlank()) throw new BusinessException(400, where + ".id 不能为空");

            String kind = col.path("kind").asText(null);
            if (kind == null || !VALID_KINDS.contains(kind)) {
                throw new BusinessException(400, where + ".kind 必须为 PRODUCT_TOTAL 或 TAB_PAIR");
            }

            if (!col.hasNonNull("threshold") || !col.path("threshold").isNumber()) {
                throw new BusinessException(400, where + ".threshold 必须为数字");
            }

            if ("TAB_PAIR".equals(kind)) {
                for (String key : new String[]{"quoteComponentId", "quoteMetric", "costingComponentId", "costingMetric"}) {
                    String v = col.path(key).asText(null);
                    if (v == null || v.isBlank()) {
                        throw new BusinessException(400, where + "." + key + " 在 kind=TAB_PAIR 时不能为空");
                    }
                }
            }
            idx++;
        }
    }

    private JsonNode parseColumnsOrNull(String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank()) return null;
        try {
            return MAPPER.readTree(columnsJson);
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── data ─────────────────────────

    /** 逐销售料号 × 两侧 × 逐页签取值矩阵（api.md §2）。 */
    public ComparisonDataDTO getData(UUID quotationId, boolean frozen) {
        List<LineSnapshot> snapshots = frozen ? loadFrozenSnapshots(quotationId) : loadLiveSnapshots(quotationId);

        // partNo 并集聚合：presence 由「该 partNo 是否有非空 quoteJson/costingJson 原始字符串」决定
        // （hasQuote/hasCosting 原始标志，而非解析是否成功）——报价/核价card值本就落在同一
        // QuotationLineItem 行上（见类注释单源纪律）；同 partNo 多行（如历史脏数据）取先到者，不合并冲突值。
        // 注意：presence 与「是否有值」分开判断——即使该侧 JSON 存在但解析退化为空（如失败哨兵
        // {"tabs":[]}），presence 仍算「有」，只是 productTotal/tabs 呈现为空，不得因解析结果误判成对侧独占。
        Map<String, RowAgg> byPartNo = new LinkedHashMap<>();
        for (LineSnapshot ls : snapshots) {
            if (ls.partNo == null || ls.partNo.isBlank()) continue;
            boolean hasQuote = ls.quoteJson != null && !ls.quoteJson.isBlank();
            boolean hasCosting = ls.costingJson != null && !ls.costingJson.isBlank();
            if (!hasQuote && !hasCosting) continue;

            RowAgg agg = byPartNo.computeIfAbsent(ls.partNo, k -> new RowAgg());
            if (agg.productName == null && ls.productName != null) agg.productName = ls.productName;
            if (hasQuote && !agg.hasQuote) {
                agg.hasQuote = true;
                agg.quote = extractSide(ls.quoteJson);
            }
            if (hasCosting && !agg.hasCosting) {
                agg.hasCosting = true;
                agg.costing = extractSide(ls.costingJson);
            }
        }

        ComparisonDataDTO dto = new ComparisonDataDTO();
        dto.rows = new ArrayList<>(byPartNo.size());
        for (var e : byPartNo.entrySet()) {
            RowAgg agg = e.getValue();
            ComparisonDataDTO.RowDTO row = new ComparisonDataDTO.RowDTO();
            row.partNo = e.getKey();
            row.productName = agg.productName;
            row.presence = agg.hasQuote && agg.hasCosting ? "BOTH"
                    : agg.hasQuote ? "QUOTE_ONLY" : "COSTING_ONLY";
            row.quote = agg.hasQuote ? toSideDTOPresent(agg.quote) : null;
            row.costing = agg.hasCosting ? toSideDTOPresent(agg.costing) : null;
            dto.rows.add(row);
        }
        return dto;
    }

    /** live：与编辑态 Tab 同源 —— 懒算补齐（幂等，已算的零开销）后读持久化卡片值。 */
    private List<LineSnapshot> loadLiveSnapshots(UUID quotationId) {
        cardSnapshotService.ensureCardValues(quotationId);
        QuotationDTO dto = quotationService.getById(quotationId);
        List<LineSnapshot> out = new ArrayList<>();
        if (dto.lineItems != null) {
            for (QuotationDTO.LineItemDTO li : dto.lineItems) {
                if (li == null) continue;
                out.add(new LineSnapshot(li.productPartNo, li.productName, li.quoteCardValues, li.costingCardValues));
            }
        }
        return out;
    }

    /**
     * frozen：报价侧读 {@code costingOrder.frozenDto}（提交时序列化的 QuotationDTO，逐字节不变）；
     * 核价侧用 {@code costingOrder.costingRender} 覆盖（已应用版本 override 的渲染缓存）——entry 缺失
     * 或其 costingCardValues 为空时降级为 frozenDto 里原有的核价字段值，与前端
     * {@code CostingReviewPage.buildFrozenView} 同一降级算法（只读，不重算）。
     * 找不到 CostingOrder（如仍是未提交的 DRAFT）时优雅降级为 live，不报错、不阻断视图。
     */
    private List<LineSnapshot> loadFrozenSnapshots(UUID quotationId) {
        CostingOrder co = CostingOrder.findActiveByQuotation(quotationId);
        if (co == null) co = CostingOrder.findLatestByQuotation(quotationId);
        if (co == null || co.frozenDto == null || co.frozenDto.isBlank()) {
            LOG.infof("[comparison-view] frozen=true 但报价单 %s 无 CostingOrder/frozenDto，降级为 live", quotationId);
            return loadLiveSnapshots(quotationId);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(co.frozenDto);
        } catch (Exception e) {
            LOG.warnf("[comparison-view] frozenDto 解析失败 quotationId=%s: %s，降级为 live", quotationId, e.getMessage());
            return loadLiveSnapshots(quotationId);
        }

        Map<String, JsonNode> renderMap = new HashMap<>();
        if (co.costingRender != null && !co.costingRender.isBlank()) {
            try {
                JsonNode renderRoot = MAPPER.readTree(co.costingRender);
                renderRoot.fields().forEachRemaining(e -> renderMap.put(e.getKey(), e.getValue()));
            } catch (Exception ignore) {
                // 覆盖缓存损坏/缺失：不覆盖，原样用 frozenDto 自带的核价值（优雅降级）
            }
        }

        List<LineSnapshot> out = new ArrayList<>();
        JsonNode lineItems = root.path("lineItems");
        if (lineItems.isArray()) {
            for (JsonNode li : lineItems) {
                String id = textOrNull(li.path("id"));
                String partNo = textOrNull(li.path("productPartNo"));
                String productName = textOrNull(li.path("productName"));
                String quoteJson = textOrNull(li.path("quoteCardValues"));
                String costingJson = textOrNull(li.path("costingCardValues"));

                JsonNode overlay = id != null ? renderMap.get(id) : null;
                if (overlay != null) {
                    String overlayCosting = textOrNull(overlay.path("costingCardValues"));
                    if (overlayCosting != null) costingJson = overlayCosting; // 覆盖缺失时保留 frozenDto 原值
                }
                out.add(new LineSnapshot(partNo, productName, quoteJson, costingJson));
            }
        }
        return out;
    }

    /**
     * 从卡片值 JSON（{@code CardSnapshotService} 输出，形如 {@code {tabs:[{componentId,componentType,
     * tabName,subtotal,subtotalByColumn}]}}）原样抽取 productTotal + 逐页签 tabTotal/subtotals。
     * 不重算，只读已写好的字段（AC-3 单源纪律）。
     */
    private SideValues extractSide(String cardValuesJson) {
        if (cardValuesJson == null || cardValuesJson.isBlank()) return null;
        JsonNode root;
        try {
            root = MAPPER.readTree(cardValuesJson);
        } catch (Exception e) {
            return null;
        }
        JsonNode tabsNode = root.path("tabs");
        if (!tabsNode.isArray()) return null;

        SideValues sv = new SideValues();
        sv.tabs = new LinkedHashMap<>();
        for (JsonNode tab : tabsNode) {
            String cid = tab.path("componentId").asText("");
            if (cid.isBlank()) continue;

            TabVal tv = new TabVal();
            JsonNode sub = tab.path("subtotal");
            if (!sub.isMissingNode() && !sub.isNull() && sub.isNumber()) {
                tv.tabTotal = BigDecimal.valueOf(sub.asDouble());
            }
            JsonNode byCol = tab.path("subtotalByColumn");
            if (byCol.isObject() && byCol.size() > 0) {
                Map<String, BigDecimal> m = new LinkedHashMap<>();
                byCol.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    if (v != null && v.isNumber()) {
                        m.put(e.getKey(), BigDecimal.valueOf(v.asDouble()));
                    }
                });
                if (!m.isEmpty()) tv.subtotals = m;
            }
            sv.tabs.put(cid, tv);

            // productTotal = SUBTOTAL 组件的独立公式值（首个命中；同 CostingSubtotalUtil#extractUnitSubtotal
            // 的取数算法：找 componentType==SUBTOTAL 的 tab，取其 subtotal，非各页签小计加总）。
            if (sv.productTotal == null && "SUBTOTAL".equals(tab.path("componentType").asText(null))) {
                sv.productTotal = tv.tabTotal;
            }
        }
        return sv;
    }

    /**
     * 该侧「presence 已判定为有」时的 DTO 组装：sv==null（JSON 存在但解析退化，如失败哨兵
     * {@code {"tabs":[]}})时仍返回一个非 null 的空壳 SideDTO（productTotal=null, tabs={}），
     * 不得因解析结果把「有但空」误判为「无」（对称于 getData 的 hasQuote/hasCosting 原始标志判断）。
     */
    private ComparisonDataDTO.SideDTO toSideDTOPresent(SideValues sv) {
        ComparisonDataDTO.SideDTO dto = new ComparisonDataDTO.SideDTO();
        dto.tabs = new LinkedHashMap<>();
        if (sv == null) return dto;
        dto.productTotal = sv.productTotal;
        for (var e : sv.tabs.entrySet()) {
            ComparisonDataDTO.TabValueDTO tv = new ComparisonDataDTO.TabValueDTO();
            tv.tabTotal = e.getValue().tabTotal;
            tv.subtotals = e.getValue().subtotals;
            dto.tabs.put(e.getKey(), tv);
        }
        return dto;
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    // ───────────────────────── 内部载体（不对外暴露，仅本类内部传值） ─────────────────────────

    private static final class LineSnapshot {
        final String partNo;
        final String productName;
        final String quoteJson;
        final String costingJson;

        LineSnapshot(String partNo, String productName, String quoteJson, String costingJson) {
            this.partNo = partNo;
            this.productName = productName;
            this.quoteJson = quoteJson;
            this.costingJson = costingJson;
        }
    }

    private static final class RowAgg {
        String productName;
        boolean hasQuote;
        boolean hasCosting;
        SideValues quote;
        SideValues costing;
    }

    private static final class SideValues {
        BigDecimal productTotal;
        Map<String, TabVal> tabs;
    }

    private static final class TabVal {
        BigDecimal tabTotal;
        Map<String, BigDecimal> subtotals;
    }
}
