package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-0729 B0 · 通道 B 升版重算通道 —— 单一入口，执行单位 =「报价单 × line item」。
 *
 * <p>完整 8 步（backtask.md §1 B0）：S0 L3 口径守卫 → S1 读版本价 → S2 定位价格字段 →
 * S3a/S3b 字段级写回 snapshot_rows/row_data → S4a/S4b 清手工值 → S5 重算卡片
 * （报价侧+核价侧）→ S6 写回行金额 → S7 失效导出快照 → S8 聚合单据。
 *
 * <p>🔒 <b>本次提交只实现 S1 + S2</b>（骨架 + 版本价读取 + 价格承载组件定位），
 * S0/S3~S8 留待后续提交按同一节奏逐步补齐（每步一次独立可验证提交，见 RECORD.md 交接说明）。
 * 未实现的步骤当前直接跳过，{@link #upgrade} 返回预览态结果（不写库），不冒充"已升版"。
 *
 * <p><b>dryRun</b>：从骨架起就是入口参数（E14-3/B4 要求）—— S1~S6 全部实现后，
 * {@code dryRun=true} 时全程只读、S3a/S3b/S6/S7/S8 的写库分支会被跳过，供审核页预算复用同一段代码。
 */
@ApplicationScoped
public class MaterialVersionUpgradeService {

    private static final Logger LOG = Logger.getLogger(MaterialVersionUpgradeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 🔒 活单白名单（E14-2，唯一常量，禁止另起第二份定义）。同时服务三处：
     * <ol>
     *   <li>本服务的升版更新范围——只更新落在这 5 个状态里的报价单行；</li>
     *   <li>裁决 33「判断依据单」选取（B4 待办池用：取该料号建单日期倒序首张活单）；</li>
     *   <li>D5「无活单料号」判定——不在任何一张这 5 个状态的单中 = 无活单，不进待办池但指针照常推进。</li>
     * </ol>
     * 🚨 禁止写成"排除 EXPIRED/CANCELLED，其余全更新"——那会把 {@code SENT}（客户手上那份，
     * 内容不该再对得上）和 {@code ACCEPTED}（已成交，同理不该再变）卷进来。
     */
    public static final Set<String> ACTIVE_STATUSES =
        Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "COSTING_REJECTED");

    /** L3 守卫阈值默认值（元，E14-11）。系统参数表（api.md §6 settings）尚未建，暂硬编码，可配置化留待后续。 */
    public static final BigDecimal DEFAULT_SUBTOTAL_GUARD_THRESHOLD = new BigDecimal("0.01");

    @Inject
    EntityManager em;

    /**
     * 单一入口。执行单位 =「报价单 × line item」。
     *
     * @param lineItemId      要升版的 quotation_line_item.id
     * @param targetVersionId 目标 element_price_version.id（S1 从其明细读一套元素价）
     * @param dryRun          true=预算试算（S1~S6 全在内存不写库，供 B4 审核页复用）；
     *                        false=正式执行。🔒 本次提交暂无实际写库步骤，两者行为一致（预留位）。
     */
    @Transactional
    public UpgradeResult upgrade(UUID lineItemId, UUID targetVersionId, boolean dryRun) {
        if (lineItemId == null || targetVersionId == null) {
            return UpgradeResult.failed("BAD_REQUEST", "lineItemId/targetVersionId 不能为空");
        }

        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) {
            return UpgradeResult.failed("LINE_ITEM_NOT_FOUND", "line item 不存在: " + lineItemId);
        }
        Quotation q = Quotation.findById(li.quotationId);
        if (q == null) {
            return UpgradeResult.failed("QUOTATION_NOT_FOUND", "报价单不存在: " + li.quotationId);
        }
        // 活单范围校验：升版只更新活单里的行；非活单（SENT/ACCEPTED/EXPIRED/CANCELLED）直接跳过不算失败
        // ——D5：这些单本就不在更新范围内，是设计内的"不处理"，不是错误。
        if (!ACTIVE_STATUSES.contains(q.status)) {
            UpgradeResult r = new UpgradeResult();
            r.status = UpgradeResult.Status.SKIPPED;
            r.message = "报价单状态 " + q.status + " 不在活单范围内（ACTIVE_STATUSES），跳过";
            r.dryRun = dryRun;
            return r;
        }

        UpgradeResult result = new UpgradeResult();
        result.dryRun = dryRun;
        result.oldSubtotal = li.subtotal;

        // ---- S1：读版本价（一套，报价核价共用，E12）。🔒 不走视图、不走取价函数——
        //      版本明细就是权威快照，这是裁决 41「预算与实际逐位一致」结构上不可能违反的关键。
        Map<String, ElementPrice> versionPrices = loadVersionPrices(targetVersionId);
        result.versionPriceCount = versionPrices.size();
        if (versionPrices.isEmpty()) {
            LOG.warnf("[b0-upgrade] li=%s targetVersion=%s 版本明细为空（无 current_price 非空的元素）",
                lineItemId, targetVersionId);
        }

        // ---- S2：定位价格字段。🔒 直接读组件三个角色字段，运行期禁止正则解析 SQL。
        JsonNode frozenTabs = loadFrozenQuoteTabsNative(q.id);
        List<UpgradeResult.PriceBearingComponent> priceBearing = locatePriceBearingComponents(frozenTabs);
        result.priceBearingComponents = priceBearing;

        if (priceBearing.isEmpty()) {
            result.status = UpgradeResult.Status.SKIPPED;
            result.message = "该单卡片结构里没有接价格策略的组件（三角色字段均未配齐），无可升版内容";
            LOG.infof("[b0-upgrade] li=%s quotation=%s 无价格承载组件，跳过", lineItemId, q.quotationNumber);
            return result;
        }

        // ---- S3~S8：留待后续提交按同一节奏逐步补齐（见类头注释）。
        result.status = UpgradeResult.Status.SKIPPED;
        result.message = String.format(
            "S1/S2 就绪预览：版本价 %d 条，价格承载组件 %d 个；S3~S8（实际写回）尚未实现，本次未修改任何数据",
            versionPrices.size(), priceBearing.size());
        LOG.infof("[b0-upgrade] li=%s quotation=%s S1/S2 预览完成：versionPrices=%d priceBearingComponents=%d " +
                "(S3~S8 待补齐，未写库)",
            lineItemId, q.quotationNumber, versionPrices.size(), priceBearing.size());
        return result;
    }

    /**
     * S1：从 {@code element_price_version_item} 读一套元素价（{@code current_price IS NOT NULL} 才纳入——
     * 与 {@code f_material_element_price} 的 fallback 判定同一口径，无价元素本就不该参与升版覆盖）。
     * 🔒 不走视图、不走取价函数——版本明细本身就是权威快照，直接原生 SQL 读表。
     */
    Map<String, ElementPrice> loadVersionPrices(UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_code, current_price, currency " +
                "FROM element_price_version_item " +
                "WHERE version_id = :vid AND current_price IS NOT NULL")
            .setParameter("vid", versionId)
            .getResultList();
        Map<String, ElementPrice> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String elementCode = (String) r[0];
            BigDecimal price = (BigDecimal) r[1];
            String currency = (String) r[2];
            if (elementCode != null) out.put(elementCode, new ElementPrice(price, currency));
        }
        return out;
    }

    /**
     * S2：遍历该报价单冻结结构的 tabs，凡组件三个角色字段里「元素列」「元素单价列」都配齐的
     * （{@code element_currency_field} 可空），判定为「价格承载组件」，本 line item 升版时要处理它。
     * 🔒 只读组件级角色字段列，不解析 {@code sql_template}（V370 已临时回填 8 个组件，B7 完整推导算法待补）。
     */
    List<UpgradeResult.PriceBearingComponent> locatePriceBearingComponents(JsonNode frozenTabs) {
        List<UpgradeResult.PriceBearingComponent> out = new ArrayList<>();
        if (frozenTabs == null || !frozenTabs.isArray()) return out;

        // 先收集本单结构里出现的 componentId 全集，一次批量查角色字段（避免逐 tab 单查，性能纪律同 E14-7）。
        List<UUID> componentIds = new ArrayList<>();
        for (JsonNode tab : frozenTabs) {
            String cidStr = tab.path("componentId").asText("");
            if (!cidStr.isBlank()) {
                try { componentIds.add(UUID.fromString(cidStr)); } catch (IllegalArgumentException ignore) { /* skip */ }
            }
        }
        if (componentIds.isEmpty()) return out;

        Map<UUID, String[]> roleFieldsByComp = loadRoleFields(componentIds);

        for (JsonNode tab : frozenTabs) {
            String cidStr = tab.path("componentId").asText("");
            if (cidStr.isBlank()) continue;
            UUID cid;
            try { cid = UUID.fromString(cidStr); } catch (IllegalArgumentException e) { continue; }
            String[] roles = roleFieldsByComp.get(cid);
            if (roles == null) continue;
            String elementCodeField = roles[0];
            String elementPriceField = roles[1];
            String elementCurrencyField = roles[2]; // 可空
            if (elementCodeField == null || elementCodeField.isBlank()
                || elementPriceField == null || elementPriceField.isBlank()) {
                continue; // 前两项任一未配齐 → 不是价格承载组件
            }
            out.add(new UpgradeResult.PriceBearingComponent(
                cidStr,
                tab.path("componentCode").asText(null),
                tab.path("tabName").asText(""),
                elementCodeField, elementPriceField, elementCurrencyField));
        }
        return out;
    }

    /** 批量查组件三个角色字段（componentId → [elementCodeField, elementPriceField, elementCurrencyField]）。 */
    private Map<UUID, String[]> loadRoleFields(List<UUID> componentIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, element_code_field, element_price_field, element_currency_field " +
                "FROM component WHERE id = ANY(:ids)")
            .setParameter("ids", componentIds.toArray(new UUID[0]))
            .getResultList();
        Map<UUID, String[]> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.put((UUID) r[0], new String[]{(String) r[1], (String) r[2], (String) r[3]});
        }
        return out;
    }

    /**
     * 读该报价单冻结结构的 tabs 数组（{@code quotation_view_structure.structure.tabs}）。
     * 与 {@code CardSnapshotService#loadFrozenQuoteTabs} 同一数据源，本类独立发一次原生查询
     * （该方法在 CardSnapshotService 里是 private，跨包不可直接复用；本查询极简单，独立持有比
     * 改动共享引擎的方法可见性风险更低）。
     */
    private JsonNode loadFrozenQuoteTabsNative(UUID quotationId) {
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
            LOG.warnf("[b0-upgrade] loadFrozenQuoteTabsNative failed q=%s: %s", quotationId, e.getMessage());
            return null;
        }
    }
}
