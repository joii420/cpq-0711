package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.UpgradeResult;
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
import jakarta.transaction.TransactionSynchronizationRegistry;
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
 * <p>🔒 <b>本次提交实现 S1 + S2 + S3a + S3b + S4a + S4b</b>（版本价读取 + 价格承载组件定位 +
 * 字段级写回 snapshot_rows/row_data + 清理 editRows/row_data 里的陈旧手改价格）。
 * S0（L3 守卫）/S5~S8 留待后续提交按同一节奏逐步补齐。
 * S5（重算卡片）未实现前，{@code quote_card_values} 本身还没有用新价重新组装——
 * 本次提交保证的是 {@code snapshot_rows}/{@code row_data} 这两个持久化底座已经是新价、
 * 且陈旧手改覆盖已清空，这正是验收 #28（升版不被 saveDraft 回滚）与验收 #29（手工值按列清除，
 * 元素/毛重等其它键不受影响）依赖的关键前提；S5 跑起来后 {@code quote_card_values} 会自然反映新价
 * （因为它读的正是这两处已经改好的底座）。
 *
 * <p><b>dryRun</b>：语义为"整个事务最终回滚，不真正提交"，不是"跳过写库步骤"——
 * S3a/S3b 无论 dryRun 与否都会执行真实的（事务内）UPDATE，这样后续 S5（重算卡片，未实现）
 * 才能在同一事务内读到"假设已升版"的 {@code snapshot_rows} 算出预算值；
 * {@link #upgrade} 末尾若 {@code dryRun=true} 则调用
 * {@link TransactionSynchronizationRegistry#setRollbackOnly()} 整体回滚，数据库最终无痕迹。
 *
 * <p>🔒 <b>行级并发保护</b>：{@code quotation_line_component_data.row_version} 原生 SQL 乐观锁
 * （不是 JPA {@code @Version}——该列现存写入口 100% 原生 SQL，见需求说明 §11.15.5.2）。
 * S3a/S3b 对同一 {@code (line_item_id, component_id)} 合并成一次 UPDATE 一起改
 * {@code snapshot_rows}+{@code row_data}，{@code WHERE row_version = :seen}，受影响行数 0 →
 * 判「冲突」，整单回滚（{@code setRollbackOnly}），返回 {@code CONFLICT}，不覆盖、可重试。
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
    @Inject
    FormulaCalculator formulaCalculator;
    @Inject
    TransactionSynchronizationRegistry txRegistry;

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

        // ---- S3a + S3b + S4b：逐价格承载组件，字段级写回 snapshot_rows（driver 行）+
        //      row_data（手动行改新价 + 非手动行清陈旧价）。
        int totalRowsChanged = 0;
        List<String> perComponentSummary = new ArrayList<>();
        for (UpgradeResult.PriceBearingComponent pbc : priceBearing) {
            RowUpdateOutcome outcome = upgradeComponentRows(lineItemId, pbc, versionPrices);
            if (outcome.conflict) {
                txRegistry.setRollbackOnly();
                UpgradeResult r = UpgradeResult.conflict(String.format(
                    "组件 %s（%s）row_version 冲突：写回期间该行被其它操作改动，整单回滚，可重试",
                    pbc.componentCode, pbc.tabName));
                r.dryRun = dryRun;
                r.oldSubtotal = li.subtotal;
                LOG.warnf("[b0-upgrade] li=%s component=%s CONFLICT，整单回滚", lineItemId, pbc.componentCode);
                return r;
            }
            totalRowsChanged += outcome.rowsChanged;
            perComponentSummary.add(pbc.componentCode + "=" + outcome.rowsChanged);
        }

        // ---- S4a：清 quote_card_values.editRows 里价格承载 tab 的价格键（销售在渲染层单元格
        //      手改过的陈旧覆盖值）。挂在 quotation_line_item 而不是 quotation_line_component_data，
        //      故不走 row_version 乐观锁（li 是本事务内的托管实体，随 upgrade() 整体事务一起提交/回滚）。
        int editRowsCleaned = cleanEditRowOverrides(li, priceBearing, versionPrices);

        // ---- S5~S8：留待后续提交按同一节奏逐步补齐（见类头注释）。
        result.status = UpgradeResult.Status.SKIPPED;
        result.message = String.format(
            "S1~S4 完成：版本价 %d 条，价格承载组件 %d 个，snapshot_rows/row_data 共改写 %d 行（%s），" +
            "editRows 清理 %d 条；S5~S8（重算卡片/写回金额/失效导出/聚合单据）尚未实现",
            versionPrices.size(), priceBearing.size(), totalRowsChanged, String.join(", ", perComponentSummary),
            editRowsCleaned);

        // dryRun：本次 S3/S4 的写入已在当前事务内真实执行（供未来 S5 在同事务内读到新价做预算），
        // 但整个事务在方法返回前标记 rollback-only，DB 最终不落任何痕迹。
        if (dryRun) {
            txRegistry.setRollbackOnly();
        }

        LOG.infof("[b0-upgrade] li=%s quotation=%s S1~S4 完成：versionPrices=%d priceBearingComponents=%d " +
                "rowsChanged=%d editRowsCleaned=%d dryRun=%b (S5~S8 待补齐)",
            lineItemId, q.quotationNumber, versionPrices.size(), priceBearing.size(), totalRowsChanged,
            editRowsCleaned, dryRun);
        return result;
    }

    /**
     * S4a：清 {@code quotation_line_item.quote_card_values} 里各 tab 的 {@code editRows}
     * 中价格承载组件那部分的价格/货币键（销售在报价卡片单元格里手改过的陈旧覆盖值）。
     *
     * <p>editRows 条目形如 {@code {rowKey, values:{字段名: 值, ...}}}——{@code values} 是否含
     * 元素字段取决于该行历史上是否被 {@code mergeRowDataInputsIntoEdits} 合并过 row_data 全量值
     * （常见情形）。🔒 本方法按该 editRow 自身 {@code values} 里的元素字段值匹配 {@code element_code}，
     * <b>对不上就跳过、不清</b>——不做"价格承载 tab 的 editRows 一律清空"这种粗粒度处理，避免误伤
     * "元素不在本次升版范围内"的手改值（对齐 S3b/S4b「对不上就不动」的同一纪律，验收 #35 同族）。
     * 🔒 只删价格/货币两个键，不清元素字段本身（验收 #34）；其余手改字段（如同一 editRow 里若还
     * 手改过毛重）原样保留。
     *
     * @return 实际清理的 editRow 条目数（跨所有价格承载 tab 汇总，调试/日志用）。
     */
    int cleanEditRowOverrides(QuotationLineItem li, List<UpgradeResult.PriceBearingComponent> priceBearing,
                               Map<String, ElementPrice> versionPrices) {
        if (li.quoteCardValues == null || li.quoteCardValues.isBlank()) return 0;
        Map<String, UpgradeResult.PriceBearingComponent> byComponentId = new LinkedHashMap<>();
        for (UpgradeResult.PriceBearingComponent pbc : priceBearing) byComponentId.put(pbc.componentId, pbc);

        JsonNode root;
        try {
            root = MAPPER.readTree(li.quoteCardValues);
        } catch (Exception e) {
            LOG.warnf("[b0-upgrade] cleanEditRowOverrides: quote_card_values 解析失败 li=%s: %s", li.id, e.getMessage());
            return 0;
        }
        JsonNode tabs = root.path("tabs");
        if (!tabs.isArray()) return 0;

        int cleaned = 0;
        boolean touched = false;
        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            UpgradeResult.PriceBearingComponent pbc = byComponentId.get(cid);
            if (pbc == null) continue; // 不是本次升版涉及的价格承载组件，不动
            JsonNode editRows = tab.path("editRows");
            if (!editRows.isArray()) continue;
            for (JsonNode er : editRows) {
                JsonNode valuesNode = er.path("values");
                if (!(valuesNode instanceof ObjectNode)) continue;
                ObjectNode values = (ObjectNode) valuesNode;
                JsonNode ecNode = values.get(pbc.elementCodeField);
                if (ecNode == null || ecNode.isNull()) continue; // 该 editRow 没带元素字段，对不上就不动
                if (!versionPrices.containsKey(ecNode.asText())) continue; // 元素不在本次升版范围内，不动
                boolean removedAny = values.remove(pbc.elementPriceField) != null;
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank()) {
                    removedAny = (values.remove(pbc.elementCurrencyField) != null) || removedAny;
                }
                if (removedAny) { cleaned++; touched = true; }
            }
        }

        if (touched) {
            try {
                li.quoteCardValues = MAPPER.writeValueAsString(root);
            } catch (Exception e) {
                LOG.warnf("[b0-upgrade] cleanEditRowOverrides: 序列化失败 li=%s: %s", li.id, e.getMessage());
                return 0;
            }
        }
        return cleaned;
    }

    /** S3a/S3b 单组件写回结果：改了几行、是否触发 row_version 冲突。 */
    static final class RowUpdateOutcome {
        final int rowsChanged;
        final boolean conflict;
        RowUpdateOutcome(int rowsChanged, boolean conflict) {
            this.rowsChanged = rowsChanged;
            this.conflict = conflict;
        }
    }

    /**
     * S3a + S3b + S4b：对一个价格承载组件的 {@code quotation_line_component_data} 行，
     * 字段级改写 {@code snapshot_rows}（driver 行，S3a）与 {@code row_data}
     * （手动行改新价 S3b + 非手动行清陈旧价 S4b），合并成一次带 {@code row_version} 乐观锁的
     * 原生 SQL UPDATE。（S4a 清 {@code quote_card_values.editRows} 在 {@link #upgrade} 里单独处理，
     * 因为那部分数据挂在 {@code quotation_line_item} 而不是这张表。）
     *
     * <p>🚨 S3a 遍历<b>全部</b> snapshot_rows——行键=「料号+材质+元素」三元组，同一元素跨不同子件/
     * 不同材质必然多行（BOM 闭包展开的不同子件/不同材质都可能含同一元素，如 Ag），本方法逐行独立
     * 判定是否命中版本价，<b>不是</b>找到第一条匹配就停、也不是遇到匹配就 return（那会静默漏改
     * 其余同元素行，验收 #33 专测）。
     *
     * <p>🚨 S3b/S4b 按 {@code row_data} 里 {@code _origin === 'manual'} 显式判定手动行 vs 非手动行——
     * 不靠下标区分（"手动行恒在尾部"只是前端纪律，不是结构保证，AP-54 同族）。两者都按该行**元素列的
     * 值**匹配 {@code element_code}，对不上就不动（不做名称→编码兜底，验收 #35）。
     */
    RowUpdateOutcome upgradeComponentRows(UUID lineItemId, UpgradeResult.PriceBearingComponent pbc,
                                                   Map<String, ElementPrice> versionPrices) {
        UUID componentId = UUID.fromString(pbc.componentId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT snapshot_rows, row_data, row_version FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND component_id = :cid")
            .setParameter("lid", lineItemId)
            .setParameter("cid", componentId)
            .getResultList();
        if (rows.isEmpty()) {
            // 该组件在这个 line item 上还没物化过 component_data 行（如页签从未渲染过），无可升版内容。
            return new RowUpdateOutcome(0, false);
        }
        Object[] row = rows.get(0);
        String snapshotRowsJson = (String) row[0];
        String rowDataJson = (String) row[1];
        long rowVersion = ((Number) row[2]).longValue();

        ArrayNode snapshotRows = parseArray(snapshotRowsJson);
        ArrayNode rowData = parseArray(rowDataJson);

        // 只有本组件真正接了取价函数的 driver 行才需要拿 fields 做「字段名→driverRow 原始列」解析
        // （S3a 用；S3b 的 row_data 已是物化后的「字段名→值」平铺结构，不需要这层解析，见 S3b 内联注释）。
        JsonNode fieldsNode = loadComponentFields(componentId);

        int changed = 0;

        // ---- S3a：driver 行（snapshot_rows），逐行遍历，不是找到第一条就停。
        for (JsonNode rowNode : snapshotRows) {
            JsonNode driverRowNode = rowNode.path("driverRow");
            if (!(driverRowNode instanceof ObjectNode)) continue;
            ObjectNode driverRow = (ObjectNode) driverRowNode;
            JsonNode basicDataValues = rowNode.path("basicDataValues");

            // 元素编码可能来自 BASIC_DATA（如 COMP-0049「元素代码」）或 INPUT_TEXT+default_source
            // （如 mc_view「元素」，SQL 原始列带 _ 前缀，字段名不带）——统一走既有字段解析器，
            // 不自己再拼一套 default_source 解析（对齐 S2 "运行期禁止正则解析 SQL" 的精神）。
            Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
                fieldsNode, driverRow, basicDataValues, null, null);
            Object elementCodeVal = resolved.get(pbc.elementCodeField);
            if (elementCodeVal == null) continue; // 该行解析不出元素编码，不动
            ElementPrice ep = versionPrices.get(elementCodeVal.toString());
            if (ep == null) continue; // 元素不在本版明细里（含无价/不在策略清单），不动

            // 只改价格/货币两个键；价格列/货币列在价格策略 SQL 契约里就是"别名逐字=字段名、不加前缀"
            // （task-0729 §11.15.3.4 纪律2），driverRow 直接以字段名为 key 持有该值，无需再经 default_source。
            if (ep.price != null) driverRow.put(pbc.elementPriceField, ep.price);
            if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank() && ep.currency != null) {
                driverRow.put(pbc.elementCurrencyField, ep.currency);
            }
            changed++;
        }

        // ---- S3b（手动行改新价）+ S4b（非手动行清手工陈旧价）合并一次遍历 row_data。
        // row_data 无论手动行还是驱动行的 autosave 持久化当前值，都是「字段名→值」平铺结构
        // （RowDataMaterializer 产物），元素编码统一按字段名直接取值，无需 resolveRowByFieldName。
        for (JsonNode rd : rowData) {
            if (!(rd instanceof ObjectNode)) continue;
            ObjectNode dataRow = (ObjectNode) rd;
            // 🚨 手动行判定：显式按 _origin === 'manual'，不靠下标区分
            // （"手动行恒在尾部"只是前端纪律不是结构保证，AP-54 同族）。
            boolean isManual = "manual".equals(dataRow.path("_origin").asText(""));

            JsonNode ecNode = dataRow.get(pbc.elementCodeField);
            if (ecNode == null || ecNode.isNull()) continue; // 对不上就不动（S3b/S4b 共同前提，验收 #35）
            ElementPrice ep = versionPrices.get(ecNode.asText());
            if (ep == null) continue; // 元素不在本版明细里，不动——既不改价也不清价

            if (isManual) {
                // S3b：手动行按元素值命中，直接改新价（不做名称→编码兜底）。
                if (ep.price != null) dataRow.put(pbc.elementPriceField, ep.price);
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank() && ep.currency != null) {
                    dataRow.put(pbc.elementCurrencyField, ep.currency);
                }
            } else {
                // S4b：非手动行 = 驱动行 autosave 持久化的当前输入值快照，可能含销售手改过的陈旧
                // 单价（mergeRowDataInputsIntoEdits 会把它合并进 editRows 抢占渲染）。🔒 只删价格
                // 字段这一个键（+货币键，若配置），不是覆盖成新值；其余手改字段（毛重/损耗率等）
                // 原样保留；🔒 不得清元素字段（否则两次升版结果漂移，验收 #34）。
                dataRow.remove(pbc.elementPriceField);
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank()) {
                    dataRow.remove(pbc.elementCurrencyField);
                }
            }
            changed++;
        }

        if (changed == 0) {
            return new RowUpdateOutcome(0, false); // 没有行命中版本价，不发 UPDATE（幂等、少写一次）
        }

        String newSnapshotRowsJson = writeJson(snapshotRows);
        String newRowDataJson = writeJson(rowData);

        // 🔒 原生 SQL 自带乐观锁：WHERE ... AND row_version = :seen，受影响行数 0 → 冲突。
        // 不是 JPA @Version——这张表的 snapshot_rows/row_data 现存写入口 100% 原生 SQL（§11.15.5.2）。
        int updated = em.createNativeQuery(
                "UPDATE quotation_line_component_data " +
                "SET snapshot_rows = CAST(:sr AS jsonb), row_data = CAST(:rd AS jsonb), " +
                "    row_version = row_version + 1 " +
                "WHERE line_item_id = :lid AND component_id = :cid AND row_version = :seen")
            .setParameter("sr", newSnapshotRowsJson)
            .setParameter("rd", newRowDataJson)
            .setParameter("lid", lineItemId)
            .setParameter("cid", componentId)
            .setParameter("seen", rowVersion)
            .executeUpdate();

        if (updated == 0) {
            return new RowUpdateOutcome(changed, true); // row_version 不匹配 → 冲突
        }
        return new RowUpdateOutcome(changed, false);
    }

    private JsonNode loadComponentFields(UUID componentId) {
        try {
            Object fieldsObj = em.createNativeQuery("SELECT fields FROM component WHERE id = :id")
                .setParameter("id", componentId)
                .getSingleResult();
            return fieldsObj == null ? MAPPER.createArrayNode() : MAPPER.readTree(fieldsObj.toString());
        } catch (Exception e) {
            LOG.warnf("[b0-upgrade] loadComponentFields failed component=%s: %s", componentId, e.getMessage());
            return MAPPER.createArrayNode();
        }
    }

    private ArrayNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? (ArrayNode) n : MAPPER.createArrayNode();
        } catch (Exception e) {
            LOG.warnf("[b0-upgrade] parseArray failed: %s", e.getMessage());
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
