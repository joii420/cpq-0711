package com.cpq.dataset.importer;

import com.cpq.common.exception.BusinessException;
import com.cpq.dataset.dto.DatasetImportResultDTO;
import com.cpq.dataset.dto.DatasetSheetSummaryDTO;
import com.cpq.dataset.dto.DsValidationError;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.Headers;
import com.cpq.dataset.versioning.AxisKey;
import com.cpq.dataset.versioning.PlainTableWriter;
import com.cpq.dataset.versioning.VersionedGroupWriter;
import com.cpq.importexcel.entity.ImportRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三套数据集通用的两阶段导入器（task-260902 · B-6 + B-7 · 需求文档 R-7）。
 *
 * <pre>
 * Phase 1  解析 + 全量校验   ← 零写库，事务外
 * Phase 2  单事务写入        ← 任一异常整体回滚
 * </pre>
 *
 * <h3>为什么拆成两个方法</h3>
 * {@link #parseAndValidate} 上<b>没有</b> {@code @Transactional}，{@link #writeAll} 上才有。
 * 这样「Phase 1 绝对零写库」不是靠自觉，而是靠结构保证 —— 校验阶段压根不在事务里。
 * 这是 AC-6 / AC-10「导入前后 45 张表 {@code count(*)} 逐表相等」能成立的唯一实现方式
 * （{@code RECORD.md} task0709 的成熟模式）。
 *
 * <h3>🚫 N+1（B-12 / AC-44）</h3>
 * 每个 sheet 的 SQL 条数是常数，与料号数无关：
 * <ul>
 *   <li>校验：主数据每类 1 条 {@code IN} 查询（全文件共享），类型 / 长度来自 Registry 常量，零查询；</li>
 *   <li>带版本写入：锁 1 + 读现状 1 + 历史最大版本 1 + 归档 1 + 删除 1 + 插入 ceil(行数/500)；</li>
 *   <li>免版本写入：ceil(行数/500) 条 upsert。</li>
 * </ul>
 * 🚫 <b>逐轴值调 {@code writeGroup} 是违规形态</b> —— 这里统一走
 * {@link VersionedGroupWriter#writeGroups} 批量入口。
 */
@ApplicationScoped
public class DatasetImportService {

    private static final Logger LOG = Logger.getLogger(DatasetImportService.class);

    @Inject DatasetSheetParser parser;
    @Inject DatasetImportValidator validator;
    @Inject VersionedGroupWriter versionedWriter;
    @Inject PlainTableWriter plainWriter;

    /** Phase 1 的产物：已解析且已通过校验的 sheet 集合。 */
    public record Prepared(DatasetRegistry registry, List<ParsedSheet> sheets) {}

    /**
     * Phase 2 的逐 sheet 进度回调（task-260907 · B-4，<b>纯加法</b>）。
     *
     * <p>加它的唯一理由：新链路（{@code QuotationImportService}）要把 {@code progress.current}
     * 报成<b>正在写的 sheet 名</b>（api.md §2），而 {@link #writeAll} 是单事务整体写完的，
     * 从外面看不到 sheet 边界。
     *
     * <p>🚫 <b>本回调不改变任何既有行为</b>：{@link #writeAll(Prepared, String)} 两参版本原样保留，
     * 内部传 {@link #NO_PROGRESS}（空实现）。{@code POST /dataset/{dataset}/import}
     * （【基础资料维护】共用，AC-15）走的就是两参版本，一个字节的行为差异都没有。
     *
     * <p>🚫 实现方<b>不许在此回调里做重活</b>（它跑在 Phase 2 的事务线程上）——
     * 节流判据必须由实现方自己把握，见 {@code QuotationImportService} 的检查点分桶。
     */
    @FunctionalInterface
    public interface SheetProgress {
        /**
         * @param index      当前 sheet 的下标（0 起）
         * @param total      本次 Phase 2 要写的 sheet 总数
         * @param sheetName  当前 sheet 的中文名
         */
        void onSheetStart(int index, int total, String sheetName);
    }

    /** 空实现：既有两参 {@link #writeAll(Prepared, String)} 用它，等价于「没有这个回调」。 */
    public static final SheetProgress NO_PROGRESS = (i, t, n) -> {};

    /**
     * Phase 1 的<b>追加校验</b>钩子（task-260907 · B-15，<b>纯加法</b>）。
     *
     * <p>加它的唯一理由：有些校验规则<b>本类拿不到判据</b>。
     * B-15 要判「客户料号 sheet 的 {@code customer_no} 是不是本次导入选定的那个客户」，
     * 而「本次选定客户」是<b>建单链路独有的输入</b>（D-10/D-15）——
     * {@code POST /dataset/{dataset}/import}（【基础资料维护】共用）压根没有这个入参。
     * ⇒ 判据只能由调用方提供，本类负责的是<b>把它并进同一批错误里</b>。
     *
     * <h3>🚨 为什么是钩子，而不是让调用方自己在 parseAndValidate 之后再查一遍</h3>
     * 「错误一次列全」（AC-4 / task-260902 AC-10）要求整份文件的<b>全部</b>问题一次报出。
     * 若追加校验在 {@code parseAndValidate} <b>抛出之后</b>才跑，那它永远跑不到 ——
     * 用户会先看到 D-19 的错、改完重传、才看到 B-15 的错，改一次传一次。
     * 挂成钩子才能让两类错误<b>同批返回</b>。
     *
     * <p>🚫 <b>不改变任何既有行为</b>：{@link #parseAndValidate(DatasetRegistry, byte[])} 两参版本
     * 原样保留，内部传 {@link #NO_EXTRA_VALIDATION}（恒返空表）。维护端走的就是两参版本，
     * 一个字节的行为差异都没有（AC-15）。
     *
     * <p>🚫 实现方<b>不许在这里查库</b> —— Phase 1 的铁律是<b>绝对零写库</b>且循环体内零查询
     * （AC-4「16 张表 count(*) 逐表相等」靠的就是它）。需要主数据比对的走
     * {@code DatasetImportValidator} 的批量预取，不要在这里逐行查。
     */
    @FunctionalInterface
    public interface ExtraValidation {
        /**
         * @param reg    本次数据集 Registry
         * @param sheets 已解析的全部 sheet（<b>只读</b>，🚫 不要在这里改行值）
         * @return 追加的错误；空表示无追加问题
         */
        List<DsValidationError> validate(DatasetRegistry reg, List<ParsedSheet> sheets);
    }

    /** 空实现：既有两参 {@link #parseAndValidate(DatasetRegistry, byte[])} 用它。 */
    public static final ExtraValidation NO_EXTRA_VALIDATION = (reg, sheets) -> List.of();

    // ==================================================================
    // Phase 1 —— 事务外，零写库
    // ==================================================================

    /**
     * 解析 + 全量校验。
     *
     * @throws DatasetValidationException 校验未通过，携带<b>全部</b>错误（不是第一条，AC-10）
     */
    public Prepared parseAndValidate(DatasetRegistry reg, byte[] bytes) {
        return parseAndValidate(reg, bytes, NO_EXTRA_VALIDATION);
    }

    /**
     * 解析 + 全量校验 + <b>调用方追加的校验</b>（task-260907 · B-15）。
     *
     * <p>语义与 {@link #parseAndValidate(DatasetRegistry, byte[])} 完全一致，只是在抛出<b>之前</b>
     * 把 {@code extra} 产出的错误并进同一批 —— 保证「错误一次列全」跨两类校验都成立。
     *
     * <p>顺序：先本类的标准校验（含 D-19「客户编号是否存在」），再 {@code extra}
     * （含 B-15「是不是本次这个客户」）。两条同型不同判据，报告里标准校验在前。
     *
     * @throws DatasetValidationException 校验未通过，携带<b>全部</b>错误（不是第一条，AC-4）
     */
    public Prepared parseAndValidate(DatasetRegistry reg, byte[] bytes, ExtraValidation extra) {
        Map<String, SheetDef> byName = new LinkedHashMap<>();
        for (SheetDef s : reg.sheets()) byName.put(Headers.normalize(s.sheetName), s);

        List<ParsedSheet> parsed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Excel 里有、Registry 里没有的 sheet（AC-34）。按 workbook 顺序收集 —— AC-34 断言「报告首条」。
            Map<String, Sheet> present = new LinkedHashMap<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                String norm = Headers.normalize(s.getSheetName());
                if (byName.containsKey(norm)) present.put(norm, s);
                else unknown.add(s.getSheetName());
            }
            // 按 Registry 声明顺序解析 → 错误报告顺序 = sheet 声明顺序
            for (SheetDef spec : reg.sheets()) {
                parsed.add(parser.parse(present.get(Headers.normalize(spec.sheetName)), spec));
            }
        } catch (java.io.IOException e) {
            throw new BusinessException(400, "Excel 解析失败：" + e.getMessage());
        }

        // 标准校验（含 D-19「客户编号是否存在于 customer.code」）
        List<DsValidationError> errors = new ArrayList<>(validator.validate(reg, parsed, unknown));
        // 调用方追加的校验（含 B-15「是不是本次导入选定的那个客户」）。
        // 🚨 必须在 throw 之前并进来 —— 放到 throw 之后就永远跑不到（见 ExtraValidation javadoc）。
        errors.addAll(extra.validate(reg, parsed));
        if (!errors.isEmpty()) {
            throw new DatasetValidationException(
                    "导入校验未通过，共 " + errors.size() + " 处问题，本次未写入任何数据", errors);
        }
        return new Prepared(reg, parsed);
    }

    // ==================================================================
    // Phase 2 —— 整份一个事务
    // ==================================================================

    /**
     * 写入。整份一个事务，任一异常整体回滚（R-7 / AC-11）。
     *
     * @param operator 操作人（写 {@code created_by} / {@code updated_by} / {@code archived_by}）
     */
    @Transactional
    public List<DatasetSheetSummaryDTO> writeAll(Prepared prepared, String operator, String customerNo) {
        return writeAll(prepared, operator, customerNo, NO_PROGRESS);
    }

    /**
     * 写入的<b>唯一权威实现</b>（四参）。整份一个事务，任一异常整体回滚（R-7 / AC-11）。
     *
     * <h3>🚩 2026-09-07 合并：两条线各自加了一个三参重载，第三参类型不同</h3>
     * <ul>
     *   <li>B-4（本分支）：{@code writeAll(Prepared, String, SheetProgress)} —— 逐 sheet 进度回调</li>
     *   <li>B-8（master）：{@code writeAll(Prepared, String, String customerNo)} —— 客户维度 + 前置校验</li>
     * </ul>
     * 两者<b>在 Java 里是合法重载</b>（第三参类型不同），编译得过 —— 这恰恰是危险之处：
     * 调用方多写一个 {@code progress} 就会静默走到「不校验客户」的那个重载，
     * 而 {@code customer_no} 会被整批写成 NULL。⇒ <b>合并为一个四参权威实现，两者都必须传</b>。
     *
     * <h3>⚠️ 事务语义（AC-11 的依据，不许被重载结构破坏）</h3>
     * 三参版本对本方法是 {@code this.} <b>自调用</b>，CDI 拦截器不会再触发一次 ——
     * 事务由三参版本上的 {@code @Transactional} 开启，整份仍是<b>一个</b>事务。
     * 本方法自己的 {@code @Transactional} 只服务于「外部直接调四参版本」这条路径
     * （{@code QuotationImportService} 走的就是它）。
     *
     * <h3>🚫 原两参 writeAll(Prepared, String) 已移除</h3>
     * 合并前它在全工程<b>零调用者</b>（实测），且 master 侧早已不存在。
     * 留着它等于留一个「跳过 {@link #requireCustomerNo} 的后门」——
     * 对 {@code customerScoped()} 的 registry 而言，那正是 AC-4 要拦的事。
     *
     * @param customerNo 本次导入选定客户的 {@code customer.code}；核价两套不带客户维度可传 null
     * @param progress   逐 sheet 进度回调；不需要时传 {@link #NO_PROGRESS}
     */
    @Transactional
    public List<DatasetSheetSummaryDTO> writeAll(Prepared prepared, String operator,
                                                 String customerNo, SheetProgress progress) {
        requireCustomerNo(prepared.registry(), customerNo);
        List<DatasetSheetSummaryDTO> summary = new ArrayList<>();
        int sheetTotal = prepared.sheets().size();
        int sheetIndex = -1;
        for (ParsedSheet ps : prepared.sheets()) {
            SheetDef spec = ps.spec;
            progress.onSheetStart(++sheetIndex, sheetTotal, spec.sheetName);

            if (!spec.versioned) {
                List<Map<String, Object>> rows = new ArrayList<>(ps.rows.size());
                for (ParsedRow r : ps.rows) rows.add(r.asRowMap());
                PlainTableWriter.UpsertResult res =
                        plainWriter.upsert(spec, customerNo, rows, VersionedGroupWriter.SOURCE_IMPORT, operator);
                summary.add(DatasetSheetSummaryDTO.plain(spec.sheetName, res.inserted(), res.updated()));
                continue;
            }

            // 带版本：按【复合轴】分组（纯内存归并，🚫 循环体内无查询），再一次性批量写入。
            // task-260907 · B-4：一份 Excel 只属于一个客户 ⇒ 全部轴键共用同一个 customerNo。
            Map<AxisKey, List<Map<String, Object>>> byAxis = new LinkedHashMap<>();
            for (ParsedRow r : ps.rows) {
                byAxis.computeIfAbsent(AxisKey.of(spec, customerNo, r.get(spec.axisColumn)),
                        k -> new ArrayList<>()).add(r.asRowMap());
            }
            // 空 sheet（只有表头）→ 轴值数 0，一行不动。🚫 空 sheet != 清空（R-6 / AC-39）
            if (byAxis.isEmpty()) {
                summary.add(DatasetSheetSummaryDTO.versioned(spec.sheetName, 0, 0, 0, 0));
                continue;
            }
            Map<AxisKey, VersionedGroupWriter.Result> results = versionedWriter.writeGroups(
                    spec, byAxis, VersionedGroupWriter.SOURCE_IMPORT,
                    VersionedGroupWriter.REASON_IMPORT_UPGRADE, operator);
            int created = 0, upgraded = 0, unchanged = 0;
            for (VersionedGroupWriter.Result r : results.values()) {
                switch (r.result()) {
                    case VersionedGroupWriter.CREATED -> created++;
                    case VersionedGroupWriter.UPGRADED -> upgraded++;
                    default -> unchanged++;
                }
            }
            summary.add(DatasetSheetSummaryDTO.versioned(
                    spec.sheetName, byAxis.size(), created, upgraded, unchanged));
        }
        return summary;
    }

    // ==================================================================
    // 编排（B-8 端点调这个）
    // ==================================================================

    /** @param userId 当前登录用户 id（写 {@code import_record.imported_by}） */
    public DatasetImportResultDTO importExcel(DatasetRegistry reg, String fileName, byte[] bytes,
                                              UUID userId, String operator, String customerNo) {
        requireCustomerNo(reg, customerNo);                       // 先拦，别等解析完才发现没客户
        long t0 = System.currentTimeMillis();
        Prepared prepared = parseAndValidate(reg, bytes);         // ← 事务外，零写库
        List<DatasetSheetSummaryDTO> summary = writeAll(prepared, operator, customerNo);

        DatasetImportResultDTO out = new DatasetImportResultDTO();
        out.dataset = reg.datasetKey();
        out.fileName = fileName;
        out.summary = summary;
        out.durationMs = System.currentTimeMillis() - t0;
        out.importRecordId = recordHistory(reg, fileName, summary, userId, out.durationMs);
        LOG.infof("[dataset-import] dataset=%s file=%s durationMs=%d sheets=%d",
                reg.datasetKey(), fileName, out.durationMs, summary.size());
        return out;
    }

    /**
     * task-260907 · AC-4：报价侧导入<b>必须</b>带客户编号。
     *
     * <p>🚫 缺就 400 拒收，不许「先写进去、customer_no 留空」——
     * 那会让整批数据落在一个没有客户归属的分组里，而后续任何一次同料号导入都会把它整组删掉。
     * <p>核价两套不带客户维度，此处放行（传了也会在写入器里被拒，防口径漂移）。
     */
    private static void requireCustomerNo(DatasetRegistry reg, String customerNo) {
        if (reg != null && reg.customerScoped() && (customerNo == null || customerNo.isBlank())) {
            throw new BusinessException(400, "导入" + reg.datasetLabel() + "必须指定客户（customerNo）");
        }
    }

    /** {@code import_record.system_type}：{@code DATASET_QUOTE} / {@code DATASET_COST_BASIC} / {@code DATASET_COST_DETAIL}。 */
    public static String systemTypeOf(DatasetRegistry reg) {
        return "DATASET_" + reg.datasetKey().toUpperCase().replace('-', '_');
    }

    /**
     * 登记进现有「导入历史」表 {@code import_record}（B-8）。
     *
     * <p><b>实测结论</b>：{@code import_record.system_type} 是 {@code varchar(20)} 且<b>没有 CHECK 约束</b>
     * （该表唯一的 CHECK 是 {@code chk_ir_status}，约束的是 {@code import_status}），
     * 因此可以直接追加三个新来源类型（最长 {@code DATASET_COST_DETAIL} = 19 字符，放得下），
     * <b>无需任何迁移、无需改动现有导入代码</b>（符合 D-13）。
     *
     * <p>独立事务：登记失败不得连累已成功的业务写入 —— 导入历史是审计信息，不是业务数据。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    UUID recordHistory(DatasetRegistry reg, String fileName, List<DatasetSheetSummaryDTO> summary,
                       UUID userId, long durationMs) {
        if (userId == null) return null;      // imported_by 有 NOT NULL + FK，无用户则不登记
        try {
            ImportRecord rec = new ImportRecord();
            rec.systemType = systemTypeOf(reg);
            rec.originalFileName = fileName;
            rec.importStatus = "SUCCESS";
            rec.importedBy = userId;
            rec.metadata = toJson(reg, summary, durationMs);
            rec.persist();
            return rec.id;
        } catch (Exception e) {
            LOG.warnf(e, "[dataset-import] 导入历史登记失败（不影响业务数据）: %s", e.getMessage());
            return null;
        }
    }

    private String toJson(DatasetRegistry reg, List<DatasetSheetSummaryDTO> summary, long durationMs) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", reg.datasetKey());
            m.put("durationMs", durationMs);
            m.put("summary", summary);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }
}
