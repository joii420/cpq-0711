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
 * 元素 ∉ 调价清单                    → <b>值</b>不动（一个字节都不碰），但撤锁标记
 * 元素 ∈ 清单 且 指针有值             → 写该版本价，清手工值
 * 元素 ∈ 清单 且 指针为空             → task-0722 实时算（基准日=报价单创建日期），清手工值
 * </pre>
 * 🔑 "清手工值"的判定条件是【元素∈清单】，与指针有无值无关——与"写什么值"是两个独立判定，
 * 不合并成一个 if（本类 {@link #reconcileRows} 的 write/clear 两段分开写，故意的）。
 *
 * <p>作用域三条件（E11-4，全部成立才归位/才只读）：<b>元素∈清单 ∧ 料号∈范围 ∧ 策略启用</b>。
 *
 * <p>🔒 <b>"不动"= 值不动，不等于"标记也不动"</b>（2026-08-05 修正，原文写的是"整行一个字节
 * 都不碰"，那是 bug 不是设计）。作用域三条件任一不成立 → 该单价列<b>应恢复可编辑</b>
 * （§11.15.2.6(1)；范围维度另见需求说明 §11.2.3 补充「移出范围的料号，其单价列可编辑性随之
 * 恢复」，元素维度另见验收 #58「确认后该元素出清单、对应单价列恢复可编辑」）。
 * 只加锁不解锁 = 永久锁死。当前落地情况：
 * <pre>
 * 元素 ∉ 清单   → ✅ 已撤锁（{@link #reconcileRows} 两个循环，值不动）
 * 料号 ∉ 范围   → ✅ 已撤锁（{@link #unlockAllRows}，值不动）
 * 策略停用      → ✅ 已撤锁（{@code BatchContext.unlockOnly} 整单模式，值不动）
 * 元素清单为空   → ✅ 已撤锁（同上；财务清空清单是日常操作，不是罕见运维）
 * </pre>
 * 四个入口<b>全部收口</b>（2026-08-05，验收 #59⑤）。原先前两个短路在 {@link #prefetch}
 * （{@code return null} / {@code return ctx}），整单一个键都不动。
 * 🔒 撤的是<b>可编辑性</b>不是<b>取价</b>：裁决 5 明确料号范围不影响取价，故绝不能顺手改值。
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
    /**
     * 🚨 <b>必须开 {@code USE_BIG_DECIMAL_FOR_FLOATS}（验收 #58）</b>：本类会把整个
     * {@code snapshot_rows}/{@code row_data} 数组反序列化再原样写回，默认 ObjectMapper 把小数
     * 读成 {@code DoubleNode}，回写时按 double 最短表示输出 → <b>静默丢精度</b>
     * （实测 {@code 123456789.123456789} → {@code 123456789.12345679}，
     * {@code 2200.000000} → {@code 2200.0}）。开了之后走 {@code DecimalNode}，字面原样保留。
     *
     * <p>🔒 {@code withExactBigDecimals(true)} 同样不能省：{@code JsonNodeFactory} 默认
     * ({@code _cfgBigDecimalExact=false}) 会对 BigDecimal 调 {@code stripTrailingZeros()}，
     * 把 {@code 2200.000000} 变成 {@code 2.2E+3}（比丢精度更难看，且改变 jsonb 字面）。
     *
     * <p>🔒 {@code WRITE_BIGDECIMAL_AS_PLAIN}：禁止回写成科学计数法，保持 jsonb 里的十进制字面。
     *
     * <p><b>为什么本轮必须修</b>：改动前作用域外的行组因 {@code changed==0} 从不落 UPDATE，
     * JSON 往返压根不发生；加了「撤锁」之后，组内只要有<b>任一行</b>带锁标记就会重写<b>整个数组</b>，
     * 把本不该动的兄弟行也卷进往返 —— {@link #unlockAllRows} 那句"值一个字节都不碰"
     * 在字面层原本并不成立，这行配置才让它成立。
     */
    private static final ObjectMapper MAPPER = com.cpq.common.DecimalJacksonCustomizer.newMapper();

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
        // ⚠️ 这句注释原文是「策略不存在/未启用 → 整单不动」，2026-08-05 起已失效：#59⑤ 之后
        //    「策略未启用」不再短路，而是走 ctx.unlockOnly 逐行撤锁（见 prefetch 内注释）。
        //    prefetch 如今只在「客户不存在或 customer.code 为空」时返 null —— 连客户编号都取不到，
        //    策略、元素清单、料号范围全都无从查起，此时才真的整单不动。
        //    （本任务已三次栽在注释与实现不符上，顺手更正，不改行为。）
        if (ctx == null) return result;

        for (QuotationLineItem li : ctx.lines) {
            String materialNo = li.productPartNoSnapshot;
            if (materialNo == null || materialNo.isBlank()) continue;
            // 🔒 料号∉范围：**值一个字节都不碰**（裁决 5：范围不影响取价），但只读三条件已破 →
            //    必须撤锁，否则该单价列永久锁死（需求说明 §11.2.3 补充「移出范围的料号，其单价列
            //    可编辑性随之恢复」/ §11.15.2.6(1)）。与「元素∉清单」同构，两处一起修，不留半边。
            // unlockOnly（策略停用 / 元素清单为空，#59⑤）= 整单退出调价机制 → 无条件走撤锁分支，
            // 与「料号∉范围」同一出口。
            boolean inScope = !ctx.unlockOnly && ctx.inScope(materialNo);
            if (inScope) result.lineItemsInScope++;

            for (UpgradeResult.PriceBearingComponent pbc : ctx.priceBearingComponents) {
                RowGroup rg = ctx.rowGroups.get(li.id + "|" + pbc.componentId);
                if (rg == null) continue;
                int changed = inScope
                    ? reconcileRows(rg, pbc, materialNo, ctx)
                    : unlockAllRows(rg);
                result.rowsChanged += changed;
            }
        }
        LOG.infof("[price-reconcile] quotation=%s linesInScope=%d rowsChanged=%d",
            quotationId, result.lineItemsInScope, result.rowsChanged);
        return result;
    }

    /**
     * 单行归位：driverRow（snapshot_rows）与 row_data 的判定条件不同——
     * 写（价格/货币键 + __priceLocked/__priceVersion 标记）两侧一致，要求"元素∈清单 <b>且</b>解出了价"；
     * 清（row_data 手工陈旧价格键）只要求"元素∈清单"，与是否解出价无关（🔒 两个 if 不合并）。
     *
     * <p>🚨 <b>"清值"与"撤锁"必须同进同出</b>（验收 #47）：元素∈清单但解不出价时，删掉价格键的
     * 同时必须删掉 {@code __priceLocked}/{@code __priceVersion}。只删值不删锁 = 只读的空格 =
     * 死格（销售既拿不到系统价也填不进去），正是 §11.4.1 改判前那条已被作废的口径。
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
            if (!ctx.elementCodesInList.contains(elementCode)) {
                // 元素∉清单 → 值一个字节都不碰，但要撤锁（见本方法 javadoc「不动 = 值不动」）
                if (stripPriceLockMarks(driverRow)) changed++;
                continue;
            }

            ElementPrice ep = pointerVersionId != null
                ? (versionPrices != null ? versionPrices.get(elementCode) : null)
                : ctx.realtimePrices.get(elementCode);
            if (ep == null || ep.price == null) continue; // 解不出价 → driverRow 不动（S3a 同款精神）

            driverRow.put(pbc.elementPriceField, com.cpq.common.PrecisionPolicy.toPlainDecimalString(ep.price));
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
            if (!ctx.elementCodesInList.contains(elementCode)) {
                // 元素∉清单 → 值一个字节都不碰，但要撤锁（见本方法 javadoc「不动 = 值不动」）
                if (stripPriceLockMarks(dataRow)) changed++;
                continue;
            }

            ElementPrice ep = pointerVersionId != null
                ? (versionPrices != null ? versionPrices.get(elementCode) : null)
                : ctx.realtimePrices.get(elementCode);

            if (ep != null && ep.price != null) {
                dataRow.put(pbc.elementPriceField, com.cpq.common.PrecisionPolicy.toPlainDecimalString(ep.price));
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank() && ep.currency != null) {
                    dataRow.put(pbc.elementCurrencyField, ep.currency);
                }
                // 🔒 标记与"写得出价"绑定，与 driverRow 分支（:193 guard）同款口径 —— 价格机制
                // 真的接管了这一格，才把它锁成只读 + 打版本徽标。
                dataRow.put("__priceLocked", true);
                dataRow.put("__priceVersion", versionLabel);
            } else {
                // 元素∈清单但本次解不出价（版本明细该元素无价/实时算无数据）→ 仍需清掉陈旧手工值，
                // 不留一个不受价格机制控制的过期数字（§11.4.1 的"清手工值"独立判定）。
                dataRow.remove(pbc.elementPriceField);
                if (pbc.elementCurrencyField != null && !pbc.elementCurrencyField.isBlank()) {
                    dataRow.remove(pbc.elementCurrencyField);
                }
                // 🚨 只删值不删锁 = 只读的空格 = 死格（验收 #47 / §11.3.2.1「从无历史价则视同不在
                // 清单、解锁可编辑」）。清值时必须把锁标记一并撤掉，否则销售既拿不到系统价、也填
                // 不进去，且无绕开手段。前端 `!!(driverRow.__priceLocked ?? rawRow.__priceLocked)`
                // 会让残留在 row_data 上的 true 直接把该格渲染成 "— 🔒"。
                dataRow.remove("__priceLocked");
                dataRow.remove("__priceVersion");
            }
            changed++;
        }

        if (changed == 0) return 0;
        return persistRowGroup(rg, snapshotRows, rowData) ? changed : 0;
    }

    /**
     * 作用域外行的「撤锁」：<b>业务值一个字节都不碰，只撤 {@code __priceLocked}/{@code __priceVersion}
     * 两个可编辑性标记</b>。
     *
     * <p>用于「料号 ∉ 范围」——§11.15.2.6(1) 的只读三条件（元素∈清单 ∧ 料号∈范围 ∧ 策略启用）
     * 有一条不成立，该单价列就该恢复可编辑（需求说明 §11.2.3 补充：「移出范围的料号，其单价列
     * 可编辑性<b>随之恢复</b>」）。范围外料号与元素在不在清单无关，故整组行无差别撤锁。
     *
     * <p>🔒 <b>撤的是"可编辑性"，不是"取价"</b>：裁决 5 明确料号范围<b>不影响取价</b>（范围外的
     * 元素价该怎么算还怎么算，只是改由销售自填/实时算）。所以这里绝不能顺手改值——改值会把
     * 「不动」变成「动」，正好踩反 §11.4.1。
     *
     * @return 真正被撤掉标记的行数（没有键可删 → 0 → 不发 UPDATE，保证幂等且 rowsChanged 不虚高）
     */
    int unlockAllRows(RowGroup rg) {
        ArrayNode snapshotRows = parseArray(rg.snapshotRowsJson);
        ArrayNode rowData = parseArray(rg.rowDataJson);
        int changed = 0;
        for (JsonNode rowNode : snapshotRows) {
            if (rowNode.path("driverRow") instanceof ObjectNode driverRow && stripPriceLockMarks(driverRow)) changed++;
        }
        for (JsonNode rd : rowData) {
            if (rd instanceof ObjectNode dataRow && stripPriceLockMarks(dataRow)) changed++;
        }
        if (changed == 0) return 0;
        return persistRowGroup(rg, snapshotRows, rowData) ? changed : 0;
    }

    /**
     * 撤掉一行的两个可编辑性标记，<b>不碰任何业务键</b>。
     *
     * @return 是否真的删掉了键（调用方据此决定要不要计入 changed —— 没删掉却计数会让每次
     *         saveDraft 对全部作用域外行白写一次 UPDATE，违反 E14-7 且 rowsChanged 虚高）
     */
    private static boolean stripPriceLockMarks(ObjectNode row) {
        boolean removed = false;
        if (row.has("__priceLocked")) { row.remove("__priceLocked"); removed = true; }
        if (row.has("__priceVersion")) { row.remove("__priceVersion"); removed = true; }
        return removed;
    }

    /** 乐观锁写回 component_data（{@link #reconcileRows} 与 {@link #unlockAllRows} 共用同一段，不写第二份）。 */
    private boolean persistRowGroup(RowGroup rg, ArrayNode snapshotRows, ArrayNode rowData) {
        int updated = em.createNativeQuery(
                "UPDATE quotation_line_component_data " +
                "SET snapshot_rows = CAST(:sr AS jsonb), row_data = CAST(:rd AS jsonb), " +
                "    row_version = row_version + 1 " +
                "WHERE id = :id AND row_version = :seen")
            .setParameter("sr", writeJson(snapshotRows))
            .setParameter("rd", writeJson(rowData))
            .setParameter("id", rg.id)
            .setParameter("seen", rg.rowVersion)
            .executeUpdate();
        if (updated == 0) {
            // 归位与并发写（如同时刷新/其它归位调用）撞车：本轮不重试，下一次归位时机（下次
            // saveDraft/下次升版）会用当时的最新 row_version 重新归位，幂等，不阻断整单保存。
            LOG.warnf("[price-reconcile] component_data id=%s row_version 冲突（expected=%d），本次归位跳过", rg.id, rg.rowVersion);
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // 整单一次预取（E14-7：策略/元素清单/料号范围/指针/版本明细/冻结结构/component_data 各查一次）
    // -------------------------------------------------------------------------

    static final class BatchContext {
        /**
         * 整单「只撤锁」模式（验收 #59⑤）：作用域三条件里与料号无关的两条整体不成立 ——
         * <b>策略停用</b> 或 <b>元素清单为空</b>。此时全单每一行都该恢复可编辑，故走
         * {@link #unlockAllRows}：只撤 {@code __priceLocked}/{@code __priceVersion}，
         * <b>不取价、不改任何业务值</b>。
         *
         * <p>此模式下 prefetch 会跳过指针/版本明细/实时价/组件 fields 四组查询（都只服务于取价），
         * 只留冻结结构 + component_data 两组（定位要撤锁的行）—— E14-7 性能纪律。
         */
        boolean unlockOnly;
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

        BatchContext ctx = new BatchContext();
        boolean strategyActive = strategy != null && Boolean.TRUE.equals(strategy.enabled);
        if (strategyActive) {
            for (CustomerPriceAdjustElement e : CustomerPriceAdjustElement.listByStrategy(strategy.id)) {
                ctx.elementCodesInList.add(e.elementCode);
            }
        }

        // 🚨 验收 #59⑤：这两种情况以前分别 `return null`（策略停用）和 `return ctx`（清单为空）
        //    直接短路 → 整单锁标记一个键都不动 → 销售永久改不了单价，且无绕开手段。
        //    「停用策略」「清空元素清单」正是业务上"退出调价机制"的正规操作（后者更是财务日常），
        //    退出后必须恢复可编辑。改为标记 unlockOnly 继续往下走，逐行只撤锁。
        //
        //    ⚠️ 原 `elementCodesInList.isEmpty()` 短路的注释理由是「逐行判定会全部落元素∉清单」——
        //    那句话在写下时是对的，但 2026-08-05 给「元素∉清单」补了撤锁动作之后就失效了：
        //    全部落"元素∉清单"如今意味着"全部都要撤锁"，短路等于把撤锁跳过。**这处不一致是我们
        //    自己改出来的**，注释与代码一起在此更正。
        ctx.unlockOnly = !strategyActive || ctx.elementCodesInList.isEmpty();

        if (!ctx.unlockOnly) {
            ctx.allMode = !"SPECIFIED".equals(strategy.materialScopeMode);
            if (!ctx.allMode) {
                for (CustomerPriceAdjustMaterial m : CustomerPriceAdjustMaterial.listByStrategy(strategy.id)) {
                    ctx.specifiedMaterials.add(m.materialNo);
                }
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

        // 🔒 unlockOnly 下面四组查询（指针/版本明细/实时价/组件 fields）全部只服务于"取价"，
        //    只撤锁时一个都不需要 → 直接跳过，别白查（E14-7 性能纪律）。
        if (!ctx.unlockOnly) {
            prefetchPricing(ctx, q, customerNo, materialNos);
        }

        // 冻结结构 → 价格承载组件（两种模式都要：撤锁也得先知道哪些组件是价格承载组件）
        JsonNode frozenTabs = loadFrozenQuoteTabs(q.id);
        ctx.priceBearingComponents = upgradeService.locatePriceBearingComponents(frozenTabs);
        if (ctx.priceBearingComponents.isEmpty()) return ctx;

        List<UUID> componentIds = new ArrayList<>();
        for (UpgradeResult.PriceBearingComponent pbc : ctx.priceBearingComponents) {
            componentIds.add(UUID.fromString(pbc.componentId));
        }

        if (!ctx.unlockOnly) {
            prefetchComponentFields(ctx, componentIds);
        }

        prefetchRowGroups(ctx, componentIds);
        return ctx;
    }

    /** 取价相关的三组批量预取（指针 / 版本明细 / 实时价）。仅非 unlockOnly 模式调用。 */
    private void prefetchPricing(BatchContext ctx, Quotation q, String customerNo, Set<String> materialNos) {
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
    }

    /** 组件 fields（一次批量）。仅非 unlockOnly 模式需要——它只服务于 driverRow 的按字段名解析取价。 */
    private void prefetchComponentFields(BatchContext ctx, List<UUID> componentIds) {
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
    }

    /** component_data（一次批量，覆盖全部产品行 × 全部价格承载组件）。两种模式都要。 */
    private void prefetchRowGroups(BatchContext ctx, List<UUID> componentIds) {
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
