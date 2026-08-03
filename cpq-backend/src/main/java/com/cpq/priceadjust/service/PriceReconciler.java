package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.CustomerPriceAdjustElement;
import com.cpq.priceadjust.entity.CustomerPriceAdjustMaterial;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.QuotationPriceRevision;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * task-0729 B10 · 价格列归位机制（缺口 A8）+ 初版 R 占位行钩子。
 *
 * <p><b>归位 = 把 §11.4.1 取价优先级表幂等地应用到每一行的价格列</b>：
 * <pre>
 * 元素 ∉ 调价清单                    → 不动（整行一个字节都不碰）
 * 元素 ∈ 清单 且 指针有值             → 写该版本价，清手工值
 * 元素 ∈ 清单 且 指针为空             → task-0722 实时算（基准日=报价单创建日期），清手工值
 * </pre>
 * 🔑 "清手工值"的判定条件是【元素∈清单】，与指针有无值无关——与"写什么值"是两个独立判定，
 * 不合并成一个 if（本类 {@link #reconcileRows} 的 write/clear 两段分开写，故意的）。
 *
 * <p>作用域三条件（E11-4，全部成立才归位/才只读）：<b>元素∈清单 ∧ 料号∈范围 ∧ 策略启用</b>。
 * 范围外、停用客户的行一个字节都不碰。
 *
 * <p>🔒 <b>性能纪律（E14-7）</b>：整单一次预取（策略/元素清单/料号范围/指针/版本明细/冻结结构
 * /component_data 各查一次），{@link #prefetch} 之后逐行处理全在内存，禁止逐行查库。
 *
 * <p>三个调用时机、同一段代码：升版（{@code MaterialVersionUpgradeService} S3/S4，沿用其既有
 * 实现，未字面复用本类——见 {@link #reconcileRows} 类注释「与 S3/S4 的关系」）/ 保存
 * （{@code QuotationResource#saveDraft}，snapshot_rows 重建后调用 {@link #reconcileQuotation}）/
 * 单元格失焦（{@code quote-card-edit}，<b>本轮未接入，如实标注为已知缺口</b>——见交付说明）。
 */
@ApplicationScoped
public class PriceReconciler {

    private static final Logger LOG = Logger.getLogger(PriceReconciler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;
    @Inject FormulaCalculator formulaCalculator;
    @Inject MaterialVersionUpgradeService upgradeService; // 复用 locatePriceBearingComponents（package-private）

    // =========================================================================
    // ① 初版 R 占位行钩子（与 saveDraft 同一条链路，§11.10.6 前半段）
    // =========================================================================

    /**
     * 建单时机 = 首次保存且已有产品行（不是创建 quotation 记录那一刻——32 张单里 10 张是零产品行
     * 空壳）。若该单从未有过任何 R 记录 且 已有 ≥1 产品行 → 创建 sealed=false 占位行，snapshot
     * 三列留 NULL（不物化，saveDraft 零额外开销）；未定型期间屏 7 渲染取当前值。
     * 首次升版时由 {@code MaterialVersionUpgradeService#materializeAndSealInitialRevision} 物化+定型。
     * 幂等：已存在任何 R 记录（含已定型的）直接 no-op。
     */
    @Transactional
    public void ensureInitialRevisionPlaceholder(UUID quotationId) {
        if (quotationId == null) return;
        if (QuotationPriceRevision.anyExists(quotationId)) return;
        long lineCount = QuotationLineItem.count("quotationId", quotationId);
        if (lineCount == 0) return;

        QuotationPriceRevision placeholder = new QuotationPriceRevision();
        placeholder.quotationId = quotationId;
        placeholder.revisionNo = nextRevisionNo(quotationId);
        placeholder.basedVersionId = null;
        placeholder.sealed = false;
        // quoteCardValues/costingCardValues/snapshotRows/quoteTotalAmount 留 NULL（未定型）
        placeholder.persist();
        LOG.infof("[price-reconcile][R] quotation=%s 建单懒建初版占位行 revisionNo=%s（未定型）",
            quotationId, placeholder.revisionNo);
    }

    private String nextRevisionNo(UUID quotationId) {
        String prefix = "R" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        @SuppressWarnings("unchecked")
        List<String> existing = em.createNativeQuery(
                "SELECT revision_no FROM quotation_price_revision WHERE quotation_id = :qid AND revision_no LIKE :prefix")
            .setParameter("qid", quotationId).setParameter("prefix", prefix + "%").getResultList();
        int maxSeq = 0;
        for (String rn : existing) {
            if (rn != null && rn.length() == prefix.length() + 2) {
                try {
                    int seq = Integer.parseInt(rn.substring(prefix.length()));
                    if (seq > maxSeq) maxSeq = seq;
                } catch (NumberFormatException ignore) { /* 忽略脏数据 */ }
            }
        }
        return prefix + String.format("%02d", maxSeq + 1);
    }

    // =========================================================================
    // ② B10 归位
    // =========================================================================

    public static class ReconcileResult {
        public int lineItemsInScope;
        public int rowsChanged;
    }

    @Transactional
    public ReconcileResult reconcileQuotation(UUID quotationId) {
        ReconcileResult result = new ReconcileResult();
        Quotation q = Quotation.findById(quotationId);
        if (q == null) return result;

        BatchContext ctx = prefetch(q);
        if (ctx == null) return result; // 策略不存在/未启用 → 整单不动（策略启用是三条件之一）

        for (QuotationLineItem li : ctx.lines) {
            String materialNo = li.productPartNoSnapshot;
            if (materialNo == null || materialNo.isBlank()) continue;
            if (!ctx.inScope(materialNo)) continue; // 料号∉范围 → 整行不动
            result.lineItemsInScope++;

            for (UpgradeResult.PriceBearingComponent pbc : ctx.priceBearingComponents) {
                RowGroup rg = ctx.rowGroups.get(li.id + "|" + pbc.componentId);
                if (rg == null) continue;
                int changed = reconcileRows(rg, pbc, materialNo, ctx);
                result.rowsChanged += changed;
            }
        }
        LOG.infof("[price-reconcile] quotation=%s linesInScope=%d rowsChanged=%d",
            quotationId, result.lineItemsInScope, result.rowsChanged);
        return result;
    }

    /**
     * 单行归位：driverRow（snapshot_rows）与 row_data 的判定条件不同——
     * 写（driverRow 价格/货币键 + __priceLocked/__priceVersion 标记）要求"元素∈清单 且解出了价"；
     * 清（row_data 手工陈旧价格键）只要求"元素∈清单"，与是否解出价无关（🔒 两个 if 不合并）。
     *
     * <p>与 S3/S4（{@code MaterialVersionUpgradeService.upgradeComponentRows}）的关系：本方法是
     * 归位机制在 saveDraft/单元格编辑两个新增时机的实现；S3/S4 是升版本体既有实现（已独立验证、
     * 已合并），本轮未把 S3/S4 字面改成调用本方法（避免对已验证的升版核心路径引入重构风险），
     * 但判定条件逐条对齐（"元素在版本价 map 里才动"等价于"元素∈清单"，因为版本明细本就只收录
     * 清单内元素）——如实标注这是一个刻意的范围裁剪，非疏漏。
     */
    int reconcileRows(RowGroup rg, UpgradeResult.PriceBearingComponent pbc, String materialNo, BatchContext ctx) {
        ArrayNode snapshotRows = parseArray(rg.snapshotRowsJson);
        ArrayNode rowData = parseArray(rg.rowDataJson);
        JsonNode fieldsNode = ctx.fieldsByComponent.getOrDefault(pbc.componentId, MAPPER.createArrayNode());

        UUID pointerVersionId = ctx.pointerByMaterial.get(materialNo);
        Map<String, ElementPrice> versionPrices = pointerVersionId != null
            ? ctx.versionPricesByVersionId.get(pointerVersionId) : null;
        String versionLabel = pointerVersionId != null
            ? ctx.versionNoById.getOrDefault(pointerVersionId, pointerVersionId.toString())
            : "实时";

        int changed = 0;

        // driverRow：写价格/货币键 + 标记，仅当解出了价（同 S3a 的"找不到就不动"精神）
        for (JsonNode rowNode : snapshotRows) {
            JsonNode driverRowNode = rowNode.path("driverRow");
            if (!(driverRowNode instanceof ObjectNode)) continue;
            ObjectNode driverRow = (ObjectNode) driverRowNode;
            JsonNode basicDataValues = rowNode.path("basicDataValues");

            Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
                fieldsNode, driverRow, basicDataValues, null, null);
            Object elementCodeVal = resolved.get(pbc.elementCodeField);
            if (elementCodeVal == null) continue;
            String elementCode = elementCodeVal.toString();
            if (!ctx.elementCodesInList.contains(elementCode)) continue; // 元素∉清单 → 不动

            ElementPrice ep = pointerVersionId != null
                ? (versionPrices != null ? versionPrices.get(elementCode) : null)
                : ctx.realtimePrices.get(elementCode);
            if (ep == null || ep.price == null) continue; // 解不出价 → driverRow 不动（S3a 同款精神）

            driverRow.put(pbc.elementPriceField, ep.price);
            if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank() && ep.currency != null) {
                driverRow.put(pbc.elementCurrencyField, ep.currency);
            }
            driverRow.put("__priceLocked", true);
            driverRow.put("__priceVersion", versionLabel);
            changed++;
        }

        // row_data：清手工陈旧价格键——条件只看"元素∈清单"，与是否解出价无关（🔒 独立判定）。
        for (JsonNode rd : rowData) {
            if (!(rd instanceof ObjectNode)) continue;
            ObjectNode dataRow = (ObjectNode) rd;
            JsonNode ecNode = dataRow.get(pbc.elementCodeField);
            if (ecNode == null || ecNode.isNull()) continue;
            String elementCode = ecNode.asText();
            if (!ctx.elementCodesInList.contains(elementCode)) continue; // 元素∉清单 → 不动

            ElementPrice ep = pointerVersionId != null
                ? (versionPrices != null ? versionPrices.get(elementCode) : null)
                : ctx.realtimePrices.get(elementCode);

            if (ep != null && ep.price != null) {
                dataRow.put(pbc.elementPriceField, ep.price);
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank() && ep.currency != null) {
                    dataRow.put(pbc.elementCurrencyField, ep.currency);
                }
            } else {
                // 元素∈清单但本次解不出价（版本明细该元素无价/实时算无数据）→ 仍需清掉陈旧手工值，
                // 不留一个不受价格机制控制的过期数字（§11.4.1 的"清手工值"独立判定）。
                dataRow.remove(pbc.elementPriceField);
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank()) {
                    dataRow.remove(pbc.elementCurrencyField);
                }
            }
            dataRow.put("__priceLocked", true);
            dataRow.put("__priceVersion", versionLabel);
            changed++;
        }

        if (changed == 0) return 0;

        String newSnapshotRowsJson = writeJson(snapshotRows);
        String newRowDataJson = writeJson(rowData);
        UUID componentId = UUID.fromString(pbc.componentId);
        int updated = em.createNativeQuery(
                "UPDATE quotation_line_component_data " +
                "SET snapshot_rows = CAST(:sr AS jsonb), row_data = CAST(:rd AS jsonb), " +
                "    row_version = row_version + 1 " +
                "WHERE id = :id AND row_version = :seen")
            .setParameter("sr", newSnapshotRowsJson)
            .setParameter("rd", newRowDataJson)
            .setParameter("id", rg.id)
            .setParameter("seen", rg.rowVersion)
            .executeUpdate();
        if (updated == 0) {
            // 归位与并发写（如同时刷新/其它归位调用）撞车：本轮不重试，下一次归位时机（下次
            // saveDraft/下次升版）会用当时的最新 row_version 重新归位，幂等，不阻断整单保存。
            LOG.warnf("[price-reconcile] component_data id=%s row_version 冲突（expected=%d），本次归位跳过", rg.id, rg.rowVersion);
            return 0;
        }
        return changed;
    }

    // -------------------------------------------------------------------------
    // 整单一次预取（E14-7：策略/元素清单/料号范围/指针/版本明细/冻结结构/component_data 各查一次）
    // -------------------------------------------------------------------------

    static final class BatchContext {
        boolean allMode;
        Set<String> elementCodesInList = new HashSet<>();
        Set<String> specifiedMaterials = new HashSet<>();
        List<QuotationLineItem> lines = new ArrayList<>();
        Map<String, UUID> pointerByMaterial = new HashMap<>();
        Map<UUID, Map<String, ElementPrice>> versionPricesByVersionId = new HashMap<>();
        Map<UUID, String> versionNoById = new HashMap<>();
        Map<String, ElementPrice> realtimePrices = new HashMap<>();
        List<UpgradeResult.PriceBearingComponent> priceBearingComponents = new ArrayList<>();
        Map<String, JsonNode> fieldsByComponent = new HashMap<>();
        Map<String, RowGroup> rowGroups = new HashMap<>(); // key = lineItemId + "|" + componentId

        boolean inScope(String materialNo) {
            return allMode || specifiedMaterials.contains(materialNo);
        }
    }

    static final class RowGroup {
        UUID id;
        String snapshotRowsJson;
        String rowDataJson;
        long rowVersion;
    }

    BatchContext prefetch(Quotation q) {
        com.cpq.customer.entity.Customer customer = com.cpq.customer.entity.Customer.findById(q.customerId);
        if (customer == null || customer.code == null) return null;
        String customerNo = customer.code;

        CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (strategy == null || !Boolean.TRUE.equals(strategy.enabled)) return null; // 策略启用是三条件之一

        BatchContext ctx = new BatchContext();
        for (CustomerPriceAdjustElement e : CustomerPriceAdjustElement.listByStrategy(strategy.id)) {
            ctx.elementCodesInList.add(e.elementCode);
        }
        if (ctx.elementCodesInList.isEmpty()) return ctx; // 清单为空，逐行判定会全部落"元素∉清单"，直接短路返回空 ctx 也对，但保留结构一致性

        ctx.allMode = !"SPECIFIED".equals(strategy.materialScopeMode);
        if (!ctx.allMode) {
            for (CustomerPriceAdjustMaterial m : CustomerPriceAdjustMaterial.listByStrategy(strategy.id)) {
                ctx.specifiedMaterials.add(m.materialNo);
            }
        }

        ctx.lines = QuotationLineItem.list("quotationId", q.id);
        Set<String> materialNos = new LinkedHashSet<>();
        for (QuotationLineItem li : ctx.lines) {
            if (li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                materialNos.add(li.productPartNoSnapshot);
            }
        }
        if (materialNos.isEmpty()) return ctx;

        // 指针（一次批量）
        @SuppressWarnings("unchecked")
        List<Object[]> ptrRows = em.createNativeQuery(
                "SELECT material_no, version_id FROM material_price_version_ref " +
                "WHERE customer_no = :cno AND material_no = ANY(:mnos)")
            .setParameter("cno", customerNo).setParameter("mnos", materialNos.toArray(new String[0]))
            .getResultList();
        Set<UUID> versionIds = new LinkedHashSet<>();
        for (Object[] r : ptrRows) {
            String mno = (String) r[0];
            UUID vid = (UUID) r[1];
            ctx.pointerByMaterial.put(mno, vid);
            versionIds.add(vid);
        }

        // 版本明细（一次批量，覆盖全部涉及的版本）
        if (!versionIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> itemRows = em.createNativeQuery(
                    "SELECT version_id, element_code, current_price, currency " +
                    "FROM element_price_version_item WHERE version_id = ANY(:vids) AND current_price IS NOT NULL")
                .setParameter("vids", versionIds.toArray(new UUID[0]))
                .getResultList();
            for (Object[] r : itemRows) {
                UUID vid = (UUID) r[0];
                String ec = (String) r[1];
                java.math.BigDecimal price = (java.math.BigDecimal) r[2];
                String currency = (String) r[3];
                ctx.versionPricesByVersionId.computeIfAbsent(vid, k -> new HashMap<>()).put(ec, new ElementPrice(price, currency));
            }
            for (UUID vid : versionIds) {
                ElementPriceVersion v = ElementPriceVersion.findById(vid);
                if (v != null) ctx.versionNoById.put(vid, v.versionNo);
            }
        }

        // 实时价（一次批量，🔒 基准日 = 该报价单创建日期，禁取"执行当天"）
        LocalDate baseDate = q.createdAt != null ? q.createdAt.toLocalDate() : LocalDate.now();
        @SuppressWarnings("unchecked")
        List<Object[]> rtRows = em.createNativeQuery(
                "SELECT element_code, unit_price, currency FROM f_customer_element_price(:cno, :bd)")
            .setParameter("cno", customerNo).setParameter("bd", baseDate)
            .getResultList();
        for (Object[] r : rtRows) {
            String ec = (String) r[0];
            java.math.BigDecimal price = (java.math.BigDecimal) r[1];
            String currency = (String) r[2];
            ctx.realtimePrices.put(ec, new ElementPrice(price, currency));
        }

        // 冻结结构 → 价格承载组件（一次批量，全部产品行共用同一份 QUOTE_CARD 结构）
        JsonNode frozenTabs = loadFrozenQuoteTabs(q.id);
        ctx.priceBearingComponents = upgradeService.locatePriceBearingComponents(frozenTabs);
        if (ctx.priceBearingComponents.isEmpty()) return ctx;

        List<UUID> componentIds = new ArrayList<>();
        for (UpgradeResult.PriceBearingComponent pbc : ctx.priceBearingComponents) {
            componentIds.add(UUID.fromString(pbc.componentId));
        }

        // 组件 fields（一次批量）
        @SuppressWarnings("unchecked")
        List<Object[]> fieldRows = em.createNativeQuery(
                "SELECT id, fields FROM component WHERE id = ANY(:ids)")
            .setParameter("ids", componentIds.toArray(new UUID[0]))
            .getResultList();
        for (Object[] r : fieldRows) {
            UUID cid = (UUID) r[0];
            String fieldsJson = (String) r[1];
            try {
                ctx.fieldsByComponent.put(cid.toString(), MAPPER.readTree(fieldsJson));
            } catch (Exception e) {
                ctx.fieldsByComponent.put(cid.toString(), MAPPER.createArrayNode());
            }
        }

        // component_data（一次批量，覆盖全部产品行 × 全部价格承载组件）
        List<UUID> lineIds = new ArrayList<>();
        for (QuotationLineItem li : ctx.lines) lineIds.add(li.id);
        @SuppressWarnings("unchecked")
        List<Object[]> cdRows = em.createNativeQuery(
                "SELECT id, line_item_id, component_id, snapshot_rows, row_data, row_version " +
                "FROM quotation_line_component_data " +
                "WHERE line_item_id = ANY(:lids) AND component_id = ANY(:cids)")
            .setParameter("lids", lineIds.toArray(new UUID[0]))
            .setParameter("cids", componentIds.toArray(new UUID[0]))
            .getResultList();
        for (Object[] r : cdRows) {
            RowGroup rg = new RowGroup();
            rg.id = (UUID) r[0];
            UUID lineItemId = (UUID) r[1];
            UUID componentId = (UUID) r[2];
            rg.snapshotRowsJson = (String) r[3];
            rg.rowDataJson = (String) r[4];
            rg.rowVersion = ((Number) r[5]).longValue();
            ctx.rowGroups.put(lineItemId + "|" + componentId, rg);
        }

        return ctx;
    }

    /** 同 {@code MaterialVersionUpgradeService#loadFrozenQuoteTabsNative} 的极简查询，独立持有
     * （该方法在对方类里是 private，且查询本身极简单，独立持有比改可见性风险更低——同一模式已在
     * B0-R 用过）。 */
    private JsonNode loadFrozenQuoteTabs(UUID quotationId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT structure FROM quotation_view_structure " +
                    "WHERE quotation_id = :qid AND view_kind = 'QUOTE_CARD'")
                .setParameter("qid", quotationId)
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            JsonNode tabs = MAPPER.readTree(rows.get(0).toString()).path("tabs");
            return tabs.isArray() ? tabs : null;
        } catch (Exception e) {
            LOG.warnf("[price-reconcile] loadFrozenQuoteTabs failed q=%s: %s", quotationId, e.getMessage());
            return null;
        }
    }

    private ArrayNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? (ArrayNode) n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }
}
