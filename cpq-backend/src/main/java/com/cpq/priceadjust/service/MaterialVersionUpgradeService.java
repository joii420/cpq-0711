package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.QuotationPriceRevision;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.CostingSubtotalUtil;
import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.LineDiscountService;
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
import java.util.LinkedHashSet;
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
 * <p>🔒 <b>本次提交实现完整 S0~S8</b>——通道 B 现已闭环：S0 L3 守卫（用旧价重算比对
 * {@code li.subtotal}，超阈值直接拦下不写回）→ S1 读版本价 → S2 定位价格字段 → S3a/S3b 字段级
 * 写回 {@code snapshot_rows}/{@code row_data} → S4a/S4b 清陈旧手改价 → S5 报价侧+核价侧重算卡片
 * → S6 写回 {@code li.subtotal} + {@code LineDiscountService.recompute} → S7 失效导出快照 →
 * S8 聚合 {@code quotation.totalAmount}。
 *
 * <p>⚠️ <b>已知差异点（如实记录，非静默假设）</b>：
 * <ol>
 *   <li>backtask 原文期望 S7 的前置是"{@code ExcelViewService} 的 fallback 取数源是
 *       {@code quote_card_values}"（P4）。实测并非如此——{@code exportExcelView} 的 fallback
 *       走 {@code getExcelView → buildRowData → buildTabJoinEffectiveRows →
 *       ComponentDataEffectiveRows.compute}，读的是 {@code quotation_line_component_data.row_data}
 *       （不是 {@code quote_card_values}）。本次仍按字面实现 S7（只置空 {@code quoteExcelValues}，
 *       不新增重算代码），但存在一个真实的过渡态间隙：S4b 把非手动 {@code row_data} 条目的价格键
 *       **删除**而非更新为新价，若升版后、下一次 saveDraft 前恰好触发 Excel 导出，fallback 会读到
 *       "价格键缺失"而非"新价"。该间隙会在下一次 saveDraft（前端把渲染出的新价重新持久化进
 *       row_data）后自愈，未做进一步加固（未调用 {@code ConfigureSnapshotService
 *       .materializeLineRowData} 重物化整行 row_data——那需要额外加载 row_key_fields/tombstones，
 *       视为超出本次 S7 字面范围的加固项，留给后续）。</li>
 *   <li>S8 的"税额"：全工程未发现任何从行金额推导 {@code taxAmount} 的既有公式（{@code taxRate}/
 *       {@code taxAmount} 是纯手填字段，零业务逻辑引用），故本次只重算 {@code totalAmount}，
 *       {@code taxAmount} 原样不动——不新造一套没有先例的算法。</li>
 * </ol>
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

    /**
     * 🚨 <b>三项配置缺一不可（验收 #58 孪生写点，2026-08-05 补）</b>——与
     * {@code PriceReconciler.MAPPER} <b>必须逐字一致</b>，两者是同构写点：
     * 整数组读出 → 改 2 个键 → 整数组写回。默认 ObjectMapper 会把小数读成 {@code DoubleNode}，
     * 回写时按 double 最短表示输出，<b>静默改写数值</b>。
     *
     * <pre>
     * 缺 USE_BIG_DECIMAL_FOR_FLOATS  → 99999999999999.999999 变成 100000000000000（数值真的变了）
     *                                  50 位小数截成 double 16 位；100.000000 → 100.0
     * 缺 withExactBigDecimals(true)  → JsonNodeFactory 默认对 BigDecimal 调 stripTrailingZeros()，
     *                                  2200.000000 变成 2.2E+3（比丢精度更糟）
     * 缺 WRITE_BIGDECIMAL_AS_PLAIN   → 回写可能用科学计数法，改变 jsonb 字面
     * </pre>
     *
     * <p>⚠️ <b>本类比 PriceReconciler 影响更大</b>：它是生产主链路
     * （{@code PriceAdjustJobExecutionService#executeItem} → {@code upgrade(.., dryRun=false)}，
     * 每次审核通过的升版都走），且本 MAPPER 同时经手<b>三组</b>持久化 JSON：
     * ① {@code quotation_line_component_data.snapshot_rows/row_data}（{@link #upgradeComponentRows}）、
     * ② {@code quotation_line_item.quote_card_values}（{@code cleanEditRowOverrides} 的 readTree→write 往返）、
     * ③ {@code quotation_price_revision} 的三列快照（{@code materializeAndSealInitialRevision}）。
     * 三者此前都在被静默改写 —— 现网已出现「同版本同元素两种字面并存」
     * （{@code 171.368000} vs {@code 171.368}）。
     *
     * <p>🔒 <b>再出现第三个同构写点时，请把这三行原样抄过去</b>，不要只加第一项
     * （只加第一项会把"丢精度"换成"变形 2.2E+3"，更难发现）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

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

    /**
     * L3 守卫阈值**默认值**（元，E14-11）。
     *
     * <p>🔄 2026-08-04：系统参数表 {@code price_adjust_settings}（api.md §6.1）已随 V378 建立，
     * 运行时阈值改从 {@link PriceAdjustSettingsService#getSubtotalGuardThreshold()} 读取（验收
     * #70④「阈值可配且即时生效，不需要重启服务」）。本常量降级为**文档与兜底基准**：与 V378 的
     * 种子值、{@link PriceAdjustSettingsService#FALLBACK_SUBTOTAL_GUARD_THRESHOLD} 三处同值，
     * 保证配置化前后守卫默认行为逐字节不变。**不要再在业务分支里直接引用它做比较**。
     */
    public static final BigDecimal DEFAULT_SUBTOTAL_GUARD_THRESHOLD = new BigDecimal("0.01");

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustSettingsService settingsService;
    @Inject
    FormulaCalculator formulaCalculator;
    @Inject
    TransactionSynchronizationRegistry txRegistry;
    @Inject
    CardSnapshotService cardSnapshotService;
    @Inject
    LineDiscountService lineDiscountService;

    /**
     * 单一入口。执行单位 =「报价单 × line item」。
     *
     * @param lineItemId      要升版的 quotation_line_item.id
     * @param targetVersionId 目标 element_price_version.id（S1 从其明细读一套元素价）
     * @param dryRun          true=预算试算：S0~S8 全部在当前事务内真实执行（供 S5 读到"假设已升版"
     *                        的新数据算出预算 subtotal/totalAmount），方法返回前整体
     *                        {@code setRollbackOnly}，DB 最终无痕迹；false=正式执行，事务正常提交。
     */
    @Transactional
    public UpgradeResult upgrade(UUID lineItemId, UUID targetVersionId, boolean dryRun) {
        return upgrade(lineItemId, targetVersionId, dryRun, null);
    }

    /**
     * task-0806 · FR-3 重载：接受 {@code PriceAdjustJobExecutionService} 批量预渲染好的核价树结果。
     *
     * <p>🔒 <b>默认 {@code null} = 现状行为</b>（需求文档硬约束 3）：三参方法原样保留、委派本方法
     * 传 {@code precomputed=null}——dryRun / 预算路径（{@code PriceAdjustBudgetService}）全部走
     * 三参方法，逐位不变。只有 {@code PriceAdjustJobExecutionService#executeJob} 的批量正式升版路径
     * （{@code dryRun=false}）会传非空 {@code precomputed}。
     *
     * <p>本参数只影响 S5 的核价侧重算——原样透传给
     * {@link CardSnapshotService#refreshCostingCardValuesForLine(UUID, CardSnapshotService.PrecomputedTreeRows)}，
     * S0~S4/S6~S9 的其余步骤完全不变。
     *
     * @param precomputed {@code null} = 未参与批量预渲染，S5 按原逻辑内部调用
     *                    {@code render()}；非 {@code null} = 已批量预渲染，S5 直接消费不再重复渲染
     */
    @Transactional
    public UpgradeResult upgrade(UUID lineItemId, UUID targetVersionId, boolean dryRun,
                                  CardSnapshotService.PrecomputedTreeRows precomputed) {
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
        // task-0806：升版前 subtotal，仅供末尾日志文案用（原是 S0 守卫块内 baseline 变量的副产物；
        // S0 现按开关短路，可能整段不执行，故独立提出，取值口径与原 baseline 完全一致）。
        BigDecimal oldSubtotalForLog = li.subtotal != null ? li.subtotal : BigDecimal.ZERO;

        // ---- S0：L3 口径守卫（E14-11）。必须在 S3 动 snapshot_rows 之前跑——用【当前未改动】的
        // 数据重算一遍报价侧卡片，与已落库 li.subtotal 比对。
        //
        // 🔄 task-0806 FR-9 / D-5（2026-08-07）：整段按 price_adjust_settings.subtotal_guard_enabled
        // 开关短路，**默认关闭**——实测 123 个 job item 只响过 1 次（0.8%），却占每项 0.46s（14.3%）。
        // 逻辑下沉到 evaluateSubtotalGuard，开关打开时的行为与本次改动前逐位一致（含
        // warnCode/warnMessage/diffValue 落库路径不变）；开关关闭时直接跳过，不产生任何 warn 字段。
        S0GuardOutcome s0 = evaluateSubtotalGuard(li);
        if (s0 != null) {
            result.warnCode = s0.warnCode;
            result.warnMessage = s0.warnMessage;
            result.diffValue = s0.diffValue;
        }

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

        // ---- S9 前置（B0-R）：初版 R 定型。§11.10.6 要求"建单时（首次保存且已有产品行）就创建
        //      sealed=false + snapshot NULL 的占位行"——这一半由 saveDraft 链路的
        //      PriceReconciler#ensureInitialRevisionPlaceholder 负责（同一条链路，一起做）。这里只管
        //      "首次升版时物化+定型"这一半：若占位行已存在但未定型 → 用【此刻，S3 尚未动任何数据】
        //      的整单原貌物化 + sealed=true；若占位行完全不存在（存量单，早于本功能上线，从未走过
        //      saveDraft 钩子）→ 直接创建并当场定型，同样的懒建兜底（coordinator 已确认此限制）。
        materializeAndSealInitialRevision(q);

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
                // 方向3 T2：S0 的告警已经检出、与本次 CONFLICT 无关，不能因为换了个 result 对象就丢掉。
                r.warnCode = result.warnCode;
                r.warnMessage = result.warnMessage;
                if (result.diffValue != null) r.diffValue = result.diffValue;
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

        // ---- S5：重算卡片（报价侧 + 核价侧都算）。走既有 buildCardValues / refreshCostingCardValuesForLine，
        //      不新写第二套卡片组装逻辑。两者都读刚被 S3 改过的 snapshot_rows/row_data，自然得到新价。
        String newQuoteCardValues = cardSnapshotService.buildCardValues(li, li.templateId);
        if (newQuoteCardValues != null) li.quoteCardValues = newQuoteCardValues;
        if (q.costingCardTemplateId != null) {
            // 🔒 单行版本（不是 refreshCostingCardValues(quotationId) 那个整单批量版本）——
            // 后者会重算该报价单下全部 line item，违反"只对被升版的料号行执行重算"（硬约束1/验收#14）。
            // task-0806 FR-3：precomputed 非空时走新重载（跳过内部 render()），null 时走原方法
            // （内部仍会调用 render()，与改造前逐位一致）——两条路径最终都落到同一份 buildCostingCardValues。
            if (precomputed != null) {
                cardSnapshotService.refreshCostingCardValuesForLine(lineItemId, precomputed);
            } else {
                cardSnapshotService.refreshCostingCardValuesForLine(lineItemId);
            }
        }

        // ---- S6：写回行金额（🔓 业务方已放行）。从新 quoteCardValues 提取报价侧 SUBTOTAL →
        //      写 li.subtotal → LineDiscountService.recompute(li)。🔒 用 CostingSubtotalUtil
        //      .extractUnitSubtotal，不新写一份（与 S0 用同一个提取方法，保证口径一致）。
        BigDecimal newSubtotal = CostingSubtotalUtil.extractUnitSubtotal(li.quoteCardValues);
        li.subtotal = newSubtotal;
        lineDiscountService.recompute(li);
        result.newSubtotal = newSubtotal;

        // ---- S7：失效导出快照。ExcelViewService 的 exportExcelView（:782-786）已有 fallback 重算
        //      （quoteExcelValues 为空 → 整行走 getExcelView 的 fallback 行），不需要新增重算代码。
        li.quoteExcelValues = null;

        // ---- S8：聚合单据。quotation.total_amount = Σ 全部 line item 的 lineTotalAmount
        //      （PART 选配子件不单独计入整单），与 QuotationService.submit() 的既有聚合口径一致，
        //      不新写第二套算法。🔒 只读其它行的既有 lineTotalAmount，不对它们调 recompute()
        //      ——硬约束1"只对被升版行执行重算"，其它行的值必须是它们自己上次算出来的，不是本次现算的。
        List<QuotationLineItem> allLines = QuotationLineItem.list("quotationId", q.id);
        BigDecimal lineSum = BigDecimal.ZERO;
        for (QuotationLineItem other : allLines) {
            if ("PART".equals(other.compositeType)) continue;
            if (other.lineTotalAmount != null) lineSum = lineSum.add(other.lineTotalAmount);
        }
        q.totalAmount = lineSum.setScale(4, java.math.RoundingMode.HALF_UP);
        // taxAmount：全工程未发现任何"从行汇总推导税额"的既有公式（taxRate/taxAmount 全库零业务
        // 逻辑引用，纯手填字段），本次不新造算法，税额原样不动——如实说明，非遗漏。

        // ---- S8b（2026-08-07 修，用户实测暴露）：草稿单的单头改用【草稿口径】重算 ----
        //
        // 本方法是全工程改 li.subtotal 的 6 个写点里，**唯一跨类、且单头无人负责**的那个：
        //   其余 5 处（QuotationService:464↔666 / 1566,1587↔1514 / 2322↔2526、
        //   CardSnapshotService:670↔recomputeDraftHeaderTotals）写行总价与写单头都在同一方法体内，
        //   写的人一眼能看到要配对；本处 li.subtotal 在 priceadjust 模块、单头写点在 quotation 模块，
        //   **配对关系跨了模块边界，就没人看见了**。
        //
        // 修的是两个病，不是一个：
        //   ① original_amount 从来没被本方法写过 → 升版后停在升版前的值（用户实测：37.330516 不动）
        //   ② 上面 S8 的 total_amount 用的是【提交口径】Σ lineTotalAmount（含 ×年用量、排除 PART），
        //      对 DRAFT 单是错的口径 —— 草稿口径应是 Σ li.subtotal × finalDiscountRate/100（含 PART）。
        //      年用量为空时 lineTotalAmount=0 → 整单总额被写成 0（实测 R26080602.quote_total_amount=0.000000，
        //      而升版前的 R26080601 是 37.330516）。**没人写只是留旧值，写错口径是主动破坏。**
        //
        // 🔒 为什么调 recomputeDraftHeaderTotals 而不是就地补一行：
        //    它是单头口径的唯一实现，自带 ①DRAFT 闸门（非草稿单不被草稿口径覆写，S8 的提交口径继续生效）
        //    ②自己 findById 取托管实体（不受调用方事务边界影响）。就地补一行会复制出第二套口径，
        //    而"两套单头口径"正是 D3-28 与本缺陷的共同根因。
        //
        // 🔒 位置不可下移到 S9 之后：S9 的 updateCurrentPeriodRevision 会把 q.totalAmount 冻进
        //    quotation_price_revision.quote_total_amount（:728）。放到 S9 之后，库里的活数据虽被修好，
        //    **冻进凭据的仍是错值**，而凭据不会被任何自愈路径纠正 —— 那是不可逆的污染。
        cardSnapshotService.recomputeDraftHeaderTotals(q.id);

        // ---- S9：本期 R = 升版【后】状态（F4）。同一 V 版内多次料号升版合并进同一条
        //      （based_version_id 天然去重键，UNIQUE(quotation_id, based_version_id)）；
        //      🔒 E11-5：每次并入都必须用当前（升版后）状态整单覆写双侧快照，不能只改
        //      时间戳+追加料号列表——否则切回该 R 预览会看到"先通过的新价、后通过的旧价"。
        updateCurrentPeriodRevision(q, targetVersionId, li.productPartNoSnapshot);

        result.status = UpgradeResult.Status.SUCCESS;
        result.message = String.format(
            "升版成功：版本价 %d 条，价格承载组件 %d 个，snapshot_rows/row_data 共改写 %d 行（%s），" +
            "editRows 清理 %d 条，subtotal %s→%s，quotation.totalAmount=%s",
            versionPrices.size(), priceBearing.size(), totalRowsChanged, String.join(", ", perComponentSummary),
            editRowsCleaned, oldSubtotalForLog, newSubtotal, q.totalAmount);

        // dryRun：本次 S3~S8 的写入已在当前事务内真实执行（供 B4 审核页在同事务内读到"假设已升版"的
        // 数据算预算），但整个事务在方法返回前标记 rollback-only，DB 最终不落任何痕迹。
        if (dryRun) {
            txRegistry.setRollbackOnly();
        }

        LOG.infof("[b0-upgrade] li=%s quotation=%s 升版完成：versionPrices=%d priceBearingComponents=%d " +
                "rowsChanged=%d editRowsCleaned=%d subtotal %s->%s dryRun=%b",
            lineItemId, q.quotationNumber, versionPrices.size(), priceBearing.size(), totalRowsChanged,
            editRowsCleaned, oldSubtotalForLog, newSubtotal, dryRun);
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

    /**
     * task-0806 FR-9 / D-5：S0 L3 口径守卫，按 {@code price_adjust_settings.subtotal_guard_enabled}
     * 开关短路（AC-7 落点）。
     *
     * <p>开关关闭（默认 {@code false}）：**不调用** {@link CardSnapshotService#buildCardValues}
     * 旧价重算，直接返回 {@code null}——省下 0.46s/项（AC-7「false 时单项耗时下降 ≥ 0.3s」）。
     *
     * <p>开关打开（{@code true}）：行为与本开关引入前逐位一致——用【当前未改动】的数据重算一遍
     * 报价侧卡片，与已落库 {@code li.subtotal} 比对，差异超阈值则返回诊断信息（不阻断升版，
     * 调用方 {@link #upgrade} 只把它挂到 {@link UpgradeResult}，S1~S9 照常执行）。
     *
     * <p>🔄 方向3 T2（2026-08-06）沿革：**拦截 → 告警**。方向3 T1（49e540c6）把 li.subtotal
     * 收敛为「卡片值算完即覆盖」的单一来源后，本守卫比较的两边（后端旧价重算 vs li.subtotal）
     * **趋于恒等**——代码保留发现能力（WARN 日志 + 可查记录），只去掉阻塞。两边同源后本告警
     * 应当归零；一旦它再次响起，就说明出现了新的双端算值分叉，那正是我们要知道的事。
     *
     * <p>包内可见（非 private）：CDI {@code @ApplicationScoped} 代理只正确委派非 private 方法，
     * 与 {@link #loadVersionPrices} / {@link #upgradeComponentRows} 同一模式，供单测直接调用。
     *
     * @return {@code null} = 未触发告警（含"开关关闭"与"开关打开但差异未超阈值"两种情形）；
     *         非 {@code null} = 检出 SUBTOTAL_MISMATCH
     */
    S0GuardOutcome evaluateSubtotalGuard(QuotationLineItem li) {
        if (!settingsService.isSubtotalGuardEnabled()) {
            return null;
        }
        String oldRecomputeJson = cardSnapshotService.buildCardValues(li, li.templateId);
        BigDecimal oldRecomputed = CostingSubtotalUtil.extractUnitSubtotal(oldRecomputeJson);
        BigDecimal baseline = li.subtotal != null ? li.subtotal : BigDecimal.ZERO;
        BigDecimal diff = oldRecomputed.subtract(baseline).abs();
        // 🔒 每次升版都重新读库取阈值（不缓存）——E14-11 业务方明确的可配项，验收 #70④ 要求
        // PUT /price-adjust/settings 后即时生效、不重启服务。**禁止硬编码**。
        BigDecimal guardThreshold = settingsService.getSubtotalGuardThreshold();
        if (diff.compareTo(guardThreshold) > 0) {
            S0GuardOutcome outcome = new S0GuardOutcome();
            outcome.warnCode = "SUBTOTAL_MISMATCH";
            outcome.warnMessage = String.format(
                "L3 口径守卫告警：后端旧价重算 %s vs li.subtotal %s，差异 %s > 阈值 %s（不阻断升版）",
                oldRecomputed, baseline, diff, guardThreshold);
            outcome.diffValue = diff;
            LOG.warnf("[b0-upgrade] li=%s S0 SUBTOTAL_MISMATCH diff=%s (旧价重算=%s, li.subtotal=%s) —— 告警不阻断，继续升版",
                li.id, diff, oldRecomputed, baseline);
            return outcome;
        }
        return null;
    }

    /** {@link #evaluateSubtotalGuard} 的检出结果：告警码 + 告警原文 + 差异绝对值。 */
    static final class S0GuardOutcome {
        String warnCode;
        String warnMessage;
        BigDecimal diffValue;
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

            // 🔒 元素编码解析已抽成 resolveDriverRowElementCode —— 与裁决 39 的「相关元素」收集
            // （{@link #collectMaterialElementCodes}）共用同一份实现，杜绝"预算算的元素"与
            // "准入判定的元素"不是同一批。
            String elementCodeVal = resolveDriverRowElementCode(fieldsNode, rowNode, pbc.elementCodeField);
            if (elementCodeVal == null) continue; // 该行解析不出元素编码，不动
            ElementPrice ep = versionPrices.get(elementCodeVal);
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

            // 🔒 同上，解析抽成 resolveDataRowElementCode，与 collectMaterialElementCodes 共用。
            String elementCodeVal = resolveDataRowElementCode(dataRow, pbc.elementCodeField);
            if (elementCodeVal == null) continue; // 对不上就不动（S3b/S4b 共同前提，验收 #35）
            ElementPrice ep = versionPrices.get(elementCodeVal);
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

    /**
     * S3a 用的元素编码解析（driver 行）。{@code snapshot_rows} 的一条 row 形如
     * {@code {driverRow:{...}, basicDataValues:{...}}}；元素编码可能来自 BASIC_DATA
     * （如 COMP-0049「元素代码」）或 INPUT_TEXT+default_source（如 mc_view「元素」，SQL 原始列带
     * {@code _} 前缀而字段名不带）—— 统一走既有字段解析器，不自己再拼一套 default_source 解析
     * （对齐 S2「运行期禁止正则解析 SQL」的精神）。
     *
     * <p>🔒 抽成独立方法的唯一目的：让 {@link #collectMaterialElementCodes}（裁决 39 的「相关元素」
     * 收集）与 {@link #upgradeComponentRows}（真正改写价格的那批行）**共用同一份口径**。
     *
     * @return 解析不出（无 driverRow / 字段解析为 null）返回 {@code null}
     */
    String resolveDriverRowElementCode(JsonNode fieldsNode, JsonNode rowNode, String elementCodeField) {
        JsonNode driverRowNode = rowNode.path("driverRow");
        if (!(driverRowNode instanceof ObjectNode)) return null;
        Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
            fieldsNode, driverRowNode, rowNode.path("basicDataValues"), null, null);
        Object v = resolved.get(elementCodeField);
        return v == null ? null : v.toString();
    }

    /**
     * S3b/S4b 用的元素编码解析（{@code row_data} 行）。row_data 无论手动行还是驱动行 autosave
     * 快照都是「字段名→值」平铺结构（{@code RowDataMaterializer} 产物），直接按字段名取键，
     * 不需要 {@code resolveRowByFieldName} 那层间接寻址。
     *
     * <p>🔒 与 {@link #resolveDriverRowElementCode} 同理，供 {@link #collectMaterialElementCodes} 共用。
     */
    String resolveDataRowElementCode(JsonNode dataRow, String elementCodeField) {
        if (!(dataRow instanceof ObjectNode)) return null;
        JsonNode ec = dataRow.get(elementCodeField);
        return (ec == null || ec.isNull()) ? null : ec.asText();
    }

    /**
     * 🔒 <b>裁决 39 / §11.5.5 补丁 1 支撑</b>：收集该 line item 上「价格承载组件」里实际出现的
     * 全部元素编码 —— 即该料号的<b>「相关元素」</b>集合。
     *
     * <p><b>为什么是这个定义</b>：升版真正会改到该料号的，就是 S3a（{@code snapshot_rows}.driverRow）
     * 与 S3b/S4b（{@code row_data}）里能解析出元素编码、且该编码命中版本明细的那些行
     * （见 {@link #upgradeComponentRows}）。因此"这个料号用到哪些元素" = 这两处扫出来的编码全集。
     * 本方法与 {@code upgradeComponentRows} 走**完全相同的三步**（冻结结构 →
     * {@link #locatePriceBearingComponents} → 两个 {@code resolveXxxElementCode}），只是不改写、
     * 只收集，不存在第二套口径。
     *
     * <p>⚠️ 返回空集只代表"<b>扫不出</b>元素"（该单无价格承载组件 / 页签从未物化过 component_data /
     * 冻结结构缺失），**不代表"该料号与元素价无关"**。调用方必须按「证明不了没变」保守处理
     * （见 {@code PriceAdjustBudgetService#hasRelevantPriceChange}）。
     */
    public Set<String> collectMaterialElementCodes(UUID lineItemId) {
        Set<String> out = new LinkedHashSet<>();
        if (lineItemId == null) return out;
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) return out;

        List<UpgradeResult.PriceBearingComponent> priceBearing =
            locatePriceBearingComponents(loadFrozenQuoteTabsNative(li.quotationId));
        for (UpgradeResult.PriceBearingComponent pbc : priceBearing) {
            UUID componentId;
            try {
                componentId = UUID.fromString(pbc.componentId);
            } catch (IllegalArgumentException e) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT snapshot_rows, row_data FROM quotation_line_component_data " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId)
                .setParameter("cid", componentId)
                .getResultList();
            if (rows.isEmpty()) continue; // 该页签从未物化过 component_data，无行可扫

            Object[] row = rows.get(0);
            ArrayNode snapshotRows = parseArray((String) row[0]);
            ArrayNode rowData = parseArray((String) row[1]);
            JsonNode fieldsNode = loadComponentFields(componentId);

            for (JsonNode rowNode : snapshotRows) {
                String ec = resolveDriverRowElementCode(fieldsNode, rowNode, pbc.elementCodeField);
                if (ec != null && !ec.isBlank()) out.add(ec);
            }
            for (JsonNode rd : rowData) {
                String ec = resolveDataRowElementCode(rd, pbc.elementCodeField);
                if (ec != null && !ec.isBlank()) out.add(ec);
            }
        }
        return out;
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

    // =========================================================================
    // B0-R：R 版本快照（coordinator 补派，backtask S0~S8 遗漏；载体见验收 #17/#53/#55/#56/#63）
    // =========================================================================

    private static final class WholeQuotationSnapshot {
        String quoteCardValuesJson;
        String costingCardValuesJson;
        String snapshotRowsJson;
    }

    /**
     * 首次升版时把初版 R 定型（§11.10.6）：
     * <ul>
     *   <li>占位行已存在（saveDraft 钩子建单时创建的 sealed=false 行）→ 原地物化【此刻】
     *       （升版前，S3 尚未动任何数据）的整单原貌 + 置 sealed=true；</li>
     *   <li>占位行不存在（存量单：早于本功能上线，从未走过新的 saveDraft 钩子）→ 直接创建
     *       并当场定型，同样懒建兜底（coordinator 已确认此限制，非静默扩大范围）。</li>
     * </ul>
     * 已定型（sealed=true）则 no-op——只有"首次"升版才定型初版。
     */
    void materializeAndSealInitialRevision(Quotation q) {
        QuotationPriceRevision initial = QuotationPriceRevision.findInitial(q.id);
        if (initial != null && Boolean.TRUE.equals(initial.sealed)) {
            return; // 已定型，不是首次升版，不动初版（本期 R 由 updateCurrentPeriodRevision 单独处理）
        }
        WholeQuotationSnapshot snap = buildWholeQuotationSnapshot(q.id);
        boolean isNew = initial == null;
        if (isNew) {
            initial = new QuotationPriceRevision();
            initial.quotationId = q.id;
            initial.revisionNo = nextRevisionNo(q.id);
            initial.basedVersionId = null;
            initial.firstEffectiveAt = q.createdAt != null ? q.createdAt : java.time.OffsetDateTime.now();
        }
        initial.sealed = true;
        initial.quoteCardValues = snap.quoteCardValuesJson;
        initial.costingCardValues = snap.costingCardValuesJson;
        initial.snapshotRows = snap.snapshotRowsJson;
        initial.quoteTotalAmount = q.totalAmount;
        initial.lastUpdatedAt = java.time.OffsetDateTime.now();
        initial.persist();
        LOG.infof("[b0-upgrade][R] quotation=%s 初版 revisionNo=%s 定型（%s，升版前整单原貌已封存）",
            q.quotationNumber, initial.revisionNo, isNew ? "存量单懒建兜底" : "占位行物化");
    }

    /**
     * 本期 R = 升版【后】状态（F4）。{@code UNIQUE(quotation_id, based_version_id)} 天然充当
     * "同一 V 版内多次料号升版合并进同一条"的去重键——find-or-create 命中既有行时，🔒 E11-5
     * 要求整单双侧快照必须【覆写】为当前状态，不能只追加 upgradedMaterialNos/改时间戳。
     */
    private void updateCurrentPeriodRevision(Quotation q, UUID targetVersionId, String upgradedMaterialNo) {
        WholeQuotationSnapshot snap = buildWholeQuotationSnapshot(q.id);
        QuotationPriceRevision rev = QuotationPriceRevision.findByVersion(q.id, targetVersionId);
        boolean isNew = rev == null;
        if (isNew) {
            rev = new QuotationPriceRevision();
            rev.quotationId = q.id;
            rev.revisionNo = nextRevisionNo(q.id);
            rev.basedVersionId = targetVersionId;
            rev.firstEffectiveAt = java.time.OffsetDateTime.now();
        }
        rev.sealed = true;
        rev.quoteCardValues = snap.quoteCardValuesJson;       // 覆写，不是合并（E11-5）
        rev.costingCardValues = snap.costingCardValuesJson;   // 覆写
        rev.snapshotRows = snap.snapshotRowsJson;             // 覆写
        rev.quoteTotalAmount = q.totalAmount;
        rev.lastUpdatedAt = java.time.OffsetDateTime.now();
        rev.upgradedMaterialNos = mergeMaterialNo(rev.upgradedMaterialNos, upgradedMaterialNo);
        rev.persist();
        LOG.infof("[b0-upgrade][R] quotation=%s %s revisionNo=%s targetVersion=%s material=%s",
            q.quotationNumber, isNew ? "新建本期" : "并入既有本期", rev.revisionNo, targetVersionId, upgradedMaterialNo);
    }

    /**
     * 整单双侧快照（§11.7.0）：全部产品行 × 全部页签 × 全部行，不是稀疏存储、不只存被升版的料号——
     * 切版预览要能渲染完整原貌。结构见 {@link QuotationPriceRevision} 类注释。
     */
    private WholeQuotationSnapshot buildWholeQuotationSnapshot(UUID quotationId) {
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", quotationId);
        ObjectNode quoteMap = MAPPER.createObjectNode();
        ObjectNode costingMap = MAPPER.createObjectNode();
        ObjectNode rowsMap = MAPPER.createObjectNode();

        for (QuotationLineItem l : lines) {
            String key = l.id.toString();
            putJsonOrNull(quoteMap, key, l.quoteCardValues);
            putJsonOrNull(costingMap, key, l.costingCardValues);

            ObjectNode compMap = MAPPER.createObjectNode();
            @SuppressWarnings("unchecked")
            List<Object[]> cdRows = em.createNativeQuery(
                    "SELECT component_id, snapshot_rows FROM quotation_line_component_data WHERE line_item_id = :lid")
                .setParameter("lid", l.id).getResultList();
            for (Object[] row : cdRows) {
                String cid = row[0] != null ? row[0].toString() : "null";
                putJsonOrNull(compMap, cid, (String) row[1]);
            }
            rowsMap.set(key, compMap);
        }

        WholeQuotationSnapshot snap = new WholeQuotationSnapshot();
        snap.quoteCardValuesJson = writeJson(quoteMap);
        snap.costingCardValuesJson = writeJson(costingMap);
        snap.snapshotRowsJson = writeJson(rowsMap);
        return snap;
    }

    private void putJsonOrNull(ObjectNode target, String key, String json) {
        if (json == null || json.isBlank()) {
            target.putNull(key);
            return;
        }
        try {
            target.set(key, MAPPER.readTree(json));
        } catch (Exception e) {
            target.putNull(key);
        }
    }

    /** 版本号 = R + YYMMDD + 两位当日流水，按单 + 日期独立计数。 */
    private String nextRevisionNo(UUID quotationId) {
        String prefix = "R" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
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
                } catch (NumberFormatException ignore) { /* 忽略非两位数字尾缀的历史脏数据 */ }
            }
        }
        return prefix + String.format("%02d", maxSeq + 1);
    }

    private String mergeMaterialNo(String existingJson, String materialNo) {
        try {
            JsonNode parsed = existingJson != null && !existingJson.isBlank()
                ? MAPPER.readTree(existingJson) : MAPPER.createArrayNode();
            ArrayNode arr = parsed.isArray() ? (ArrayNode) parsed : MAPPER.createArrayNode();
            boolean found = false;
            for (JsonNode n : arr) {
                if (n.asText("").equals(materialNo)) { found = true; break; }
            }
            if (!found && materialNo != null && !materialNo.isBlank()) arr.add(materialNo);
            return writeJson(arr);
        } catch (Exception e) {
            return materialNo != null ? "[\"" + materialNo + "\"]" : "[]";
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
