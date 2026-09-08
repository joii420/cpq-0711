package com.cpq.dataset.quotation;

import com.cpq.dataset.dto.DatasetSheetSummaryDTO;
import com.cpq.dataset.dto.DsValidationError;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.importer.DatasetImportService;
import com.cpq.dataset.importer.ParsedRow;
import com.cpq.dataset.importer.ParsedSheet;
import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.QuoteRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-260907 · B-2 / B-4：报价数据导入（建单流程专用）的<b>异步编排</b>。
 *
 * <h3>本类做什么、不做什么</h3>
 * <ul>
 *   <li>✅ 做：后台线程编排「Phase 1 校验 → Phase 2 写入 → 落 {@code import_record}」+ 进度节流；</li>
 *   <li>🚫 <b>不做</b>解析、不做写库 —— 全部委托既有
 *       {@link DatasetImportService}（{@code task-260902} 交付、已被【基础资料维护】跑通的那一套）。
 *       另写一套解析/校验/写入必然与它漂移（{@code VersionedGroupWriter} 类注释里
 *       「两套实现必然漂移」的同源理由）。</li>
 *   <li>⚠️ <b>校验只有一条例外</b>：{@link #validateSingleCustomer}（B-15 / AC-3
 *       「一份 Excel 只能属于一个客户」）。它<b>必须</b>在这里，因为判据是「本次导入选定的客户」——
 *       那是建单链路独有的输入，共用的 {@code DatasetImportValidator} 拿不到。
 *       它经 {@link DatasetImportService.ExtraValidation} 钩子并进同一批错误，
 *       🚫 不是另起一套校验流程。</li>
 * </ul>
 *
 * <h3>为什么另起一个 Service 而不是给 {@code DatasetImportService} 加异步入口</h3>
 * （backtask B-2「二选一，在 test.md 说明选了哪个」→ <b>选新建</b>）
 * <ol>
 *   <li>{@code DatasetImportService} 是三套数据集共用件，被
 *       {@code POST /dataset/{dataset}/import}（维护页签，AC-15 要求逐字不变）直接消费。
 *       往它身上挂「客户 / import_record / 进度 / 建单」这些<b>只有报价建单链路才有</b>的概念，
 *       会让共用件长出一半用不上的分支；</li>
 *   <li>B-14（{@code customer_no} 注入，⛔ 阻塞中）明确要求注入点在<b>本任务新建的服务</b>里，
 *       🚫 不在共用的 {@code DatasetImportService}（那条路被维护页签用着，没有「选客户」这个输入）。
 *       现在就把编排落在这里，B-14 解封时是加一段而不是搬一次家。</li>
 * </ol>
 * ⇒ 对 {@code DatasetImportService} 的改动仅限<b>两处纯加法</b>，既有两参方法一律原样保留、
 * 内部传空实现，维护端行为零差异（AC-15）：
 * <ul>
 *   <li>{@link DatasetImportService.SheetProgress} + 三参 {@code writeAll}（B-4 进度）</li>
 *   <li>{@link DatasetImportService.ExtraValidation} + 三参 {@code parseAndValidate}（B-15 跨客户拒收）</li>
 * </ul>
 *
 * <h3>🚫 N+1</h3>
 * 本类零 SQL（除进度/终态各常数条）。真正的查询全在 {@code DatasetImportService} 内，
 * 其 N+1 纪律见该类 javadoc：每 sheet 常数条，与料号数无关。
 * 本类的三处循环（{@link #countRows} 求和、{@link #collectBatchParts} 收集批次料号、
 * {@link #validateSingleCustomer} 逐行比对客户编号）均为纯内存，循环体内零查询。
 */
@ApplicationScoped
public class QuotationImportService {

    private static final Logger LOG = Logger.getLogger(QuotationImportService.class);

    /**
     * 进度检查点数（照抄 V6 侧实测结论）：Phase 2 的 sheet 序列按「均匀分桶」切成 N 个检查点，
     * 桶号变化才写一次。16 sheet + N=2 ⇒ 精确写 2 次。
     *
     * <p>🚫 <b>不许每 sheet 写一次</b>：V6 侧实测 17 次 × 三次网络往返 ≈ 800ms，
     * 是端到端超 2s 的主因（纯事务开销，不随行数放大）。
     */
    private static final int PROGRESS_CHECKPOINT_COUNT = 2;

    /** 静默超时兜底：单个 sheet 卡得太久时即使没到检查点也强写一次，避免进度条静止被误读成「卡死」。 */
    private static final long PROGRESS_MAX_SILENCE_NANOS = 800_000_000L;

    @Inject DatasetImportService importService;
    @Inject QuoteRegistry quoteRegistry;
    @Inject QuotationImportRecordWriter recordWriter;

    /**
     * 后台执行导入。调用方（{@code QuotationImportResource}）已在<b>请求线程</b>内建好
     * {@code import_record}（PROCESSING）并把 Excel 读进 {@code bytes}。
     *
     * <p><b>{@code @ActivateRequestContext}</b>：后台线程默认没有激活的 CDI request context，
     * 不加这个注解，下游第一次用 request-scoped 的 {@code EntityManager} 就会抛
     * 「neither a transaction nor a CDI request context is active」。
     * 这不是新发明 —— 与既有 {@code QuoteImportService#processImport} 同一模式。
     *
     * <p>🚨 <b>顶层 try/catch 是硬要求</b>：后台线程的异常没有地方可抛（{@code runAsync} 的
     * {@code CompletableFuture} 没人 join），不 finalize 成 FAILED 就会永远停在 PROCESSING，
     * 前端轮询转到天荒地老 —— 比报错更糟。
     *
     * @param recordId   已建 {@code import_record} 主键（status=PROCESSING）
     * @param customerNo 本次导入选定客户的 {@code customer.code}（B-14 解封后的注入值来源）
     * @param fileName   上传文件名（仅日志用）
     * @param bytes      Excel 二进制（请求线程已读入内存，避免上传临时文件被回收）
     * @param importedBy 当前登录用户
     */
    @ActivateRequestContext
    public void processImport(UUID recordId, String customerNo, String fileName,
                              byte[] bytes, UUID importedBy) {
        long t0 = System.currentTimeMillis();
        final DatasetRegistry reg = quoteRegistry;
        final int sheetCount = reg.sheets().size();
        final int totalSteps = 2 + sheetCount;      // 解析 + 校验 + N 个 sheet 写入
        final String operator = String.valueOf(importedBy);

        // ===== Phase 1：解析 + 全量校验（事务外、零写库）=====
        // AC-4「16 张表 count(*) 逐表相等」靠的就是这里 —— parseAndValidate 上没有 @Transactional，
        // 「校验阶段零写库」是结构保证而不是自觉。
        recordWriter.updateProgress(recordId, 0, totalSteps, "解析与校验中");
        DatasetImportService.Prepared prepared;
        try {
            // B-15：把「一份 Excel 只能属于一个客户」并进同一批校验（🚫 不是校验完再查一遍，
            // 那样它永远排在 D-19 之后、只有 D-19 全通过才轮得到，用户要改两轮）。
            prepared = importService.parseAndValidate(reg, bytes,
                    (r, sheets) -> validateSingleCustomer(sheets, customerNo));
        } catch (DatasetValidationException ve) {
            // 整份拒收：一行未写，回全部错误（不是第一条）
            List<DsValidationError> errors = ve.getErrors();
            LOG.infof("[quotation-import] record=%s file=%s Phase1 拒收 errors=%d",
                    recordId, fileName, errors.size());
            recordWriter.finalizeFailed(recordId, 0, ve.getMessage(), errors);
            return;
        } catch (Exception e) {
            LOG.error("[quotation-import] Phase1 解析/校验异常 record=" + recordId, e);
            recordWriter.finalizeFailed(recordId, 0,
                    "导入失败：" + rootMessage(e), List.of());
            return;
        }

        final int totalRows = countRows(prepared.sheets());

        // ===== Phase 2：单事务写入，任一异常整体回滚 =====
        List<DatasetSheetSummaryDTO> summary;
        try {
            // 🚩 2026-09-07 合并：必须同时传 customerNo 与 progress。
            //    合并前本行是三参（只带 progress），而 master 侧把 customer_no 加成了写入维度 ——
            //    两个三参重载类型不同、编译都过，漏传 customerNo 不会报错，
            //    症状是报价侧整批数据 customer_no 写成 NULL（AC-4 要拦的正是这个）。
            summary = importService.writeAll(prepared, operator, customerNo,
                    new CheckpointProgress(recordId, totalSteps));
        } catch (DatasetValidationException ve) {
            recordWriter.finalizeFailed(recordId, totalRows, ve.getMessage(), ve.getErrors());
            return;
        } catch (Exception e) {
            LOG.error("[quotation-import] Phase2 写入失败，整单已回滚 record=" + recordId, e);
            recordWriter.finalizeFailed(recordId, totalRows,
                    "写入失败，已回滚：" + rootMessage(e), List.of());
            return;
        }

        long durationMs = System.currentTimeMillis() - t0;
        // 🔄 D-26：把本批次的客户料号清单一并落库 —— 建单候选靠它精确框定「本次导入」，
        //    见 QuotationImportRecordWriter#finalizeSuccess 的 batchParts javadoc。
        recordWriter.finalizeSuccess(recordId, totalRows, summary, durationMs,
                collectBatchParts(prepared.sheets()));
        LOG.infof("[quotation-import] record=%s customer=%s file=%s sheets=%d rows=%d durationMs=%d SUCCESS",
                recordId, customerNo, fileName, summary.size(), totalRows, durationMs);
    }

    /**
     * B-4 的节流实现：固定检查点（均匀分桶）+ 静默超时兜底，任一命中即写。
     *
     * <p>桶号 {@code i*(N-1)/(sheetTotal-1)}：首个 sheet 恒落桶 0、末个恒落桶 N-1，
     * 「关键节点必写」天然成立，不需要额外特判。
     */
    private final class CheckpointProgress implements DatasetImportService.SheetProgress {
        private final UUID recordId;
        private final int totalSteps;
        private long lastNanos = System.nanoTime();
        private int lastBucket = -1;

        CheckpointProgress(UUID recordId, int totalSteps) {
            this.recordId = recordId;
            this.totalSteps = totalSteps;
        }

        @Override
        public void onSheetStart(int index, int sheetTotal, String sheetName) {
            int bucket = sheetTotal <= 1 ? 0
                    : (int) ((long) index * (PROGRESS_CHECKPOINT_COUNT - 1) / (sheetTotal - 1));
            long now = System.nanoTime();
            boolean checkpoint = bucket != lastBucket;
            boolean silenceTimeout = (now - lastNanos) >= PROGRESS_MAX_SILENCE_NANOS;
            if (!checkpoint && !silenceTimeout) return;
            // 独立 REQUIRES_NEW 事务（经 CDI 代理调用，拦截器生效）：
            // Phase 2 整单回滚时进度不能跟着消失。
            recordWriter.updateProgress(recordId, 2 + index, totalSteps, sheetName);
            lastNanos = now;
            lastBucket = bucket;
        }
    }

    /** 纯内存求和（🚫 无查库）：本次 Excel 解析出的业务行总数，落 {@code import_record.total_rows}。 */
    private static int countRows(List<ParsedSheet> sheets) {
        int n = 0;
        for (ParsedSheet ps : sheets) n += ps.rows.size();
        return n;
    }

    /** {@code QuoteRegistry} 里「客户料号」sheet 的 key（见 {@code QuoteRegistry:62}）。 */
    private static final String CUSTOMER_PART_SHEET_KEY = "CUSTOMER_PART";

    /** 「客户料号」sheet 里客户编号列的 DB 列名 / Excel 中文列名。 */
    private static final String CUSTOMER_NO_COLUMN = "customer_no";
    private static final String CUSTOMER_NO_LABEL = "客户编号";

    /**
     * <b>B-15 / AC-3：一份 Excel 只能属于一个客户。</b>
     *
     * <p>「客户料号」sheet 里出现<b>非本次导入选定客户</b>的编号 → 整份拒收，逐行报出。
     *
     * <h3>为什么必须拦（不是洁癖，是数据会错且不报错）</h3>
     * 16 个 sheet 里<b>只有「客户料号」带客户编号列</b>，其余 15 张的 {@code customer_no}
     * 只能取自<b>导入时选定的客户</b>（D-10 / D-15）。
     * ⇒ 放行跨客户文件，会让 {@code CUST-0001} 的客户料号行指向一批
     * {@code customer_no=CUST-0004} 的物料 / BOM / 费用行 —— <b>全程不报错</b>，
     * 只是数据从此是错的。
     * <p>实测（测试代理第一轮 L1，必现 2/2）：改动前该文件返 {@code SUCCESS}，
     * 「客户料号」{@code inserted=4}，{@code CUST-0001} 那行照样写进了库。
     *
     * <h3>与 D-19 同型不同判据，两条都要</h3>
     * <ul>
     *   <li><b>D-19</b>（{@code DatasetImportValidator} 已有）判「<b>存不存在</b>」——
     *       不在 {@code customer.code} 中 → 拒收，reason「客户编号未在客户档案中登记」；</li>
     *   <li><b>B-15</b>（本方法）判「<b>是不是本次这个客户</b>」——
     *       在 {@code customer.code} 中、但 ≠ 入参客户 → 拒收。</li>
     * </ul>
     * 一行可能同时命中两条（如填了一个不存在的编号）：那时会各报一条，
     * 这是<b>刻意的</b> —— 两条说的是不同的事，合并成一条反而让用户不知道该改哪。
     *
     * <h3>🚫 N+1</h3>
     * 纯内存逐行比对，<b>零查询</b>。选定客户的 {@code code} 由调用方在请求线程内取好传进来
     * （{@code QuotationImportResource#startImport} 已 {@code Customer.findById}），
     * 这里不再查库 —— Phase 1 的铁律是绝对零写库 + 循环体内零查询。
     *
     * @param sheets     已解析的全部 sheet（只读）
     * @param customerNo 本次导入选定客户的 {@code customer.code}
     */
    private static List<DsValidationError> validateSingleCustomer(List<ParsedSheet> sheets,
                                                                  String customerNo) {
        List<DsValidationError> out = new ArrayList<>();
        if (customerNo == null || customerNo.isBlank()) return out;   // 入口已拦，防御性
        String selected = customerNo.trim();

        for (ParsedSheet ps : sheets) {
            if (!CUSTOMER_PART_SHEET_KEY.equals(ps.spec.sheetKey)) continue;
            // 表头都不对时逐行校验没有意义（与 DatasetImportValidator 同一处理）
            if (!ps.missingHeaders.isEmpty()) continue;
            for (ParsedRow row : ps.rows) {                 // 纯内存，无查库
                String v = trimOrNull(row.get(CUSTOMER_NO_COLUMN));
                // 空值不在这里报：required 分支已由标准校验报过「必填项为空」，
                // 再报一条「不是本次客户」只会让报告变吵（AC-4 同源纪律）。
                if (v == null) continue;
                if (selected.equals(v)) continue;
                // B-16：value = 文件里出现的那个外来客户编号
                out.add(new DsValidationError(ps.spec.sheetName, row.excelRow(), CUSTOMER_NO_LABEL,
                        v, crossCustomerReason(selected, v, row.excelRow())));
            }
        }
        return out;
    }

    /**
     * B-15 的 reason 文案。措辞对齐 {@code 需求文档.md} AC-3 原文
     * （「本次导入客户为 X，文件中出现其它客户编号：Y（第 N 行）」）—— <b>必须同时点名
     * 本次客户、外来编号、行号</b>，只说「客户不一致」在几百行的模板里等于没报。
     *
     * <p>📌 <b>刻意不放进 {@code DatasetValidationReasons}</b>：那个类的 javadoc 自陈是
     * <b>{@code task-260902} api.md §1 的封闭集</b>，服务的是
     * {@code POST /dataset/{dataset}/import}（【基础资料维护】共用）。
     * 本条 reason <b>只由建单链路产出</b>，维护端永远不会出现它 ——
     * 混进那份封闭集会让它变成两个任务共管，且前端 {@code ValidationErrorTable} 的逐字断言
     * 范围被悄悄扩大。⇒ 归本任务 {@code api.md} 管。
     * <p>🚦 若主线认为应当统一收进封闭集，这是个契约决定，请裁决后我再挪。
     */
    static String crossCustomerReason(String selected, String found, int excelRow) {
        return "本次导入客户为 " + selected + "，文件中出现其它客户编号：" + found
                + "（第 " + excelRow + " 行）。一份 Excel 只能属于一个客户";
    }

    /** 复合键分隔符，与 {@code DatasetImportValidator.KEY_SEP} 同一约定（业务值不可能包含）。 */
    private static final char KEY_SEP = (char) 0x1F;

    /**
     * 🔄 D-26：收集本批次的 {@code (customer_no, customer_product_no)} 清单。
     *
     * <p>数据源是 <b>Phase 1 已解析好的行</b>，不是回头查库 —— 查库拿不到「哪些行是这一批的」
     * （{@code ds_quote_customer_part} 没有批次维度列，这正是 D-26 要解决的问题本身）。
     *
     * <p>去重用 {@code LinkedHashSet}：保持 Excel 顺序（建单的 {@code ORDER BY} 之外还需要
     * 可读的诊断顺序）。同一份 Excel 内该组合本就是免版本表主键、Phase 1 已查重拒收，
     * 这里去重只是防御。
     *
     * <p>🚫 <b>N+1</b>：两层纯内存循环，无任何查询。
     */
    private static List<Map<String, String>> collectBatchParts(List<ParsedSheet> sheets) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ParsedSheet ps : sheets) {
            if (!CUSTOMER_PART_SHEET_KEY.equals(ps.spec.sheetKey)) continue;
            for (ParsedRow r : ps.rows) {                 // 纯内存，无查库
                String cust = trimOrNull(r.get("customer_no"));
                String prod = trimOrNull(r.get("customer_product_no"));
                // 两列都是该表主键 + required，Phase 1 已保证非空；空值行只可能来自未来的
                // Registry 变更，此时跳过比写一条 (null,null) 进清单安全。
                if (cust == null || prod == null) continue;
                // 分隔符 0x1F：与 DatasetImportValidator / PlainTableWriter 的复合键同一约定
                // （业务值不可能包含它），避免 'A'+'BC' 与 'AB'+'C' 撞成同一个 key。
                if (!seen.add(cust + KEY_SEP + prod)) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("customerNo", cust);
                m.put("customerProductNo", prod);
                out.add(m);
            }
        }
        return out;
    }

    private static String trimOrNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
