package com.cpq.dataset.importer;

import com.cpq.common.exception.BusinessException;
import com.cpq.dataset.dto.DatasetImportResultDTO;
import com.cpq.dataset.dto.DatasetSheetSummaryDTO;
import com.cpq.dataset.dto.DsValidationError;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.Headers;
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

    // ==================================================================
    // Phase 1 —— 事务外，零写库
    // ==================================================================

    /**
     * 解析 + 全量校验。
     *
     * @throws DatasetValidationException 校验未通过，携带<b>全部</b>错误（不是第一条，AC-10）
     */
    public Prepared parseAndValidate(DatasetRegistry reg, byte[] bytes) {
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

        List<DsValidationError> errors = validator.validate(reg, parsed, unknown);
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
    public List<DatasetSheetSummaryDTO> writeAll(Prepared prepared, String operator) {
        List<DatasetSheetSummaryDTO> summary = new ArrayList<>();
        for (ParsedSheet ps : prepared.sheets()) {
            SheetDef spec = ps.spec;

            if (!spec.versioned) {
                List<Map<String, Object>> rows = new ArrayList<>(ps.rows.size());
                for (ParsedRow r : ps.rows) rows.add(r.asRowMap());
                PlainTableWriter.UpsertResult res =
                        plainWriter.upsert(spec, rows, VersionedGroupWriter.SOURCE_IMPORT, operator);
                summary.add(DatasetSheetSummaryDTO.plain(spec.sheetName, res.inserted(), res.updated()));
                continue;
            }

            // 带版本：按轴值分组（纯内存归并，🚫 循环体内无查询），再一次性批量写入
            Map<String, List<Map<String, Object>>> byAxis = new LinkedHashMap<>();
            for (ParsedRow r : ps.rows) {
                byAxis.computeIfAbsent(r.get(spec.axisColumn), k -> new ArrayList<>()).add(r.asRowMap());
            }
            // 空 sheet（只有表头）→ 轴值数 0，一行不动。🚫 空 sheet != 清空（R-6 / AC-39）
            if (byAxis.isEmpty()) {
                summary.add(DatasetSheetSummaryDTO.versioned(spec.sheetName, 0, 0, 0, 0));
                continue;
            }
            Map<String, VersionedGroupWriter.Result> results = versionedWriter.writeGroups(
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
                                              UUID userId, String operator) {
        long t0 = System.currentTimeMillis();
        Prepared prepared = parseAndValidate(reg, bytes);         // ← 事务外，零写库
        List<DatasetSheetSummaryDTO> summary = writeAll(prepared, operator);

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
