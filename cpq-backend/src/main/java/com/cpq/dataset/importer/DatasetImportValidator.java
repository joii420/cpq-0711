package com.cpq.dataset.importer;

import com.cpq.dataset.dto.DsValidationError;
import com.cpq.dataset.fingerprint.ValueNormalizer;
import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.service.DatasetValidationReasons;
import com.cpq.dataset.support.DatasetValues;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入 Phase 1 全量校验（task-260902 · B-6 · 需求文档 R-7）。
 *
 * <h3>🚨 两条铁律</h3>
 * <ol>
 *   <li><b>绝对零写库</b>（AC-6 / AC-10）：本类只做 {@code SELECT}，不许「先建物料再校验 BOM」。
 *       这是「导入前后 45 张表 count(*) 逐表相等」能成立的唯一实现方式。
 *       结构上由 {@code DatasetImportService.parseAndValidate}（<b>无</b> {@code @Transactional}）保证。</li>
 *   <li><b>错误一次列全</b>（AC-10）：🚫 严禁 fail-fast。遇错继续扫，最后一次性返回全部错误。</li>
 * </ol>
 *
 * <h3>校验项（R-7）</h3>
 * <ul>
 *   <li>结构：sheet 名 ∈ 本数据集（AC-34）；表头列名与 Registry 一致</li>
 *   <li>必填：红底列 + <b>轴列</b>（凌驾底色，AC-7）+ 免版本表主键列（AC-45）</li>
 *   <li>类型：{@code numeric} 列可解析 BigDecimal（AC-9）；{@code integer} 列可解析整数</li>
 *   <li>长度：按 {@code ColumnDef.pgType} 的 {@code varchar(n)}，超长报错，<b>🚫 禁止静默截断</b>（AC-40）</li>
 *   <li>主数据存在性：{@code masterCheck=true} 的列批量预取（AC-8）；
 *       其中<b>客户编号严格校验</b>（D-19 / AC-45 / AC-46）—— 不在 {@code customer.code} 中整份拒收，
 *       reason 用 {@code 客户编号未在客户档案中登记}，与其他主数据的 {@code 主数据不存在} 区分</li>
 * </ul>
 *
 * <h3>N+1（B-12 / AC-44）</h3>
 * 逐行循环体内<b>零查询</b>：先全量扫一遍收集待校验编码 → 每个 masterType 一条 {@code IN} 查询
 * → 再逐行比对内存 Set。长度 / 类型元数据来自 {@code ColumnDef.pgType}（Registry 常量，零查询）。
 */
@ApplicationScoped
public class DatasetImportValidator {

    @Inject
    MasterDataChecker masters;

    /**
     * 校验全部已解析 sheet。
     *
     * @param unknownSheets Excel 里有、Registry 里没有的 sheet 名，按 workbook 顺序（AC-34）
     * @return 全部错误；空表示校验通过
     */
    public List<DsValidationError> validate(DatasetRegistry reg,
                                            List<ParsedSheet> sheets,
                                            List<String> unknownSheets) {
        List<DsValidationError> errors = new ArrayList<>();

        // ── ① sheet 级：不属于本数据集（AC-34）。
        //    放最前 —— AC-34 断言「报告首条」就是这条，🚫 后面不许再做全局排序把它挤走。
        for (String s : unknownSheets) {
            errors.add(new DsValidationError(s, 0, null,
                    DatasetValidationReasons.sheetNotInDataset(s, reg.datasetLabel())));
        }

        // ── ② 表头级：Registry 声明的 DB 列在 Excel 表头里缺失
        for (ParsedSheet ps : sheets) {
            for (String label : ps.missingHeaders) {
                errors.add(new DsValidationError(ps.spec.sheetName, 1, label,
                        DatasetValidationReasons.headerMissing(label)));
            }
        }

        // ── ③ 主数据编码批量预取（每 masterType 一条 SQL，与行数 / 料号数无关）
        Map<String, Set<String>> wanted = new HashMap<>();
        for (ParsedSheet ps : sheets) {
            if (!ps.missingHeaders.isEmpty()) continue;
            for (ColumnDef c : ps.spec.persistedColumns()) {
                String mt = masterTypeOf(c);
                if (mt == null) continue;
                Set<String> bucket = wanted.computeIfAbsent(mt, k -> new LinkedHashSet<>());
                for (ParsedRow r : ps.rows) {
                    String v = r.get(c.name);
                    if (!ValueNormalizer.isBlank(v)) bucket.add(ValueNormalizer.toRawString(v));
                }
            }
        }
        Map<String, Set<String>> existing = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : wanted.entrySet()) {
            existing.put(e.getKey(), masters.existing(e.getKey(), e.getValue()));   // ← 循环体外的常数条查询
        }

        // ── ③b 轴值登记校验（D-24 / AC-52）：带版本 sheet 的每个轴值必须已在本数据集的物料表登记
        //    判定集合 = 「本次 Excel 物料 sheet 里的轴值」∪「库中物料表已有的轴值」。
        //    前者不可省：同一份文件内「先登记后引用」是合法的 —— 本次导入会把物料表一起写进去，
        //    只看库里已有的数据会把合法文件误拒（AC-52 后半段专门验这条）。
        //    🚫 批量判定：只对「Excel 里没登记」的那部分发<b>一条</b> IN 查询，严禁逐轴值查（B-12）。
        errors.addAll(validateAxisRegistration(reg, sheets));

        // ── ④ 行级校验（🚫 循环体内零查询：N+1 自检点）
        for (ParsedSheet ps : sheets) {
            if (!ps.missingHeaders.isEmpty()) continue;
            SheetDef spec = ps.spec;
            // R-1 铁律②：免版本表的主键列一律必填，无论 Registry 的 required 怎么标（AC-45）
            Set<String> pkColumns = spec.versioned ? Set.of() : new HashSet<>(spec.primaryKeyColumns);
            List<ColumnDef> cols = spec.persistedColumns();

            for (ParsedRow row : ps.rows) {
                for (ColumnDef c : cols) {
                    String raw = row.get(c.name);

                    // 必填 / 轴列
                    if (ValueNormalizer.isBlank(raw)) {
                        if ("AXIS".equals(c.role)) {
                            errors.add(err(spec, row, c, DatasetValidationReasons.AXIS_EMPTY));
                        } else if (c.required || pkColumns.contains(c.name)) {
                            errors.add(err(spec, row, c, DatasetValidationReasons.REQUIRED_EMPTY));
                        }
                        continue;   // 空值不再做类型 / 长度 / 主数据校验
                    }

                    String v = ValueNormalizer.toRawString(raw);

                    // 类型
                    if (DatasetValues.isInteger(c)) {
                        if (ValueNormalizer.parseInteger(v) == null) {
                            errors.add(err(spec, row, c, DatasetValidationReasons.NOT_AN_INTEGER));
                            continue;
                        }
                    } else if (DatasetValues.isNumeric(c)) {
                        if (ValueNormalizer.parseDecimal(v) == null) {
                            errors.add(err(spec, row, c, DatasetValidationReasons.NOT_A_NUMBER));
                            continue;
                        }
                    } else {
                        // 长度（字符列）。🚫 超长必须报错，不许截断 —— AC-40
                        Integer max = DatasetValues.maxLength(c);
                        if (max != null && v.length() > max) {
                            errors.add(err(spec, row, c, DatasetValidationReasons.tooLong(max)));
                            continue;
                        }
                    }

                    // 主数据存在性（纯内存 Set 比对）
                    String mt = masterTypeOf(c);
                    if (mt != null) {
                        Set<String> ok = existing.getOrDefault(mt, Set.of());
                        if (!ok.contains(v)) errors.add(err(spec, row, c, masterReason(mt)));
                    }
                }
            }
        }
        return errors;
    }

    /**
     * 轴值登记校验（D-24 / AC-52）。
     *
     * <p>不这么拦的后果：轴值没在物料表登记时，数据能落库，但维护页列表的数据源正是物料表
     * （api.md §3），于是该料号在界面上<b>完全不可见</b> —— 列表没有它 → 抽屉打不开 → 9/17 个 tab
     * 一个都看不到。实测核价2 模板原本就是这个形态（物料 sheet 只登记 S-3120014539，
     * 带版本表的 4 个轴值与它交集为空）。
     *
     * <p>SQL：<b>整份文件一条</b> {@code IN (...)}，且只查「Excel 里没登记」的那部分。与轴值数无关。
     */
    private List<DsValidationError> validateAxisRegistration(DatasetRegistry reg, List<ParsedSheet> sheets) {
        List<DsValidationError> out = new ArrayList<>();
        String axisColumn = reg.axisColumn();

        // (1) 本次 Excel「物料」sheet 登记的轴值
        Set<String> registeredInFile = new HashSet<>();
        for (ParsedSheet ps : sheets) {
            if (ps.spec.versioned || !reg.materialTable().equals(ps.spec.tableName)) continue;
            if (!ps.missingHeaders.isEmpty()) continue;
            for (ParsedRow r : ps.rows) {
                String v = r.get(axisColumn);
                if (!ValueNormalizer.isBlank(v)) registeredInFile.add(ValueNormalizer.toRawString(v));
            }
        }

        // (2) 带版本 sheet 的轴值 → 首次出现位置（报告 row 用首次出现的 Excel 物理行号）
        Map<String, Object[]> firstSeen = new LinkedHashMap<>();   // axis -> {SheetDef, ParsedRow, ColumnDef}
        for (ParsedSheet ps : sheets) {
            if (!ps.spec.versioned || !ps.missingHeaders.isEmpty()) continue;
            ColumnDef axisCol = ps.spec.column(ps.spec.axisColumn);
            if (axisCol == null) continue;
            for (ParsedRow r : ps.rows) {
                String v = r.get(ps.spec.axisColumn);
                if (ValueNormalizer.isBlank(v)) continue;          // 空轴列已由 AC-7 的分支报错
                firstSeen.putIfAbsent(ValueNormalizer.toRawString(v), new Object[]{ps.spec, r, axisCol});
            }
        }

        // (3) Excel 里没登记的候选，去库里批量确认（一条 SQL）
        Set<String> candidates = new LinkedHashSet<>(firstSeen.keySet());
        candidates.removeAll(registeredInFile);
        if (candidates.isEmpty()) return out;
        Set<String> registeredInDb = masters.existingIn(reg.materialTable(), axisColumn, candidates);

        for (String axis : candidates) {
            if (registeredInDb.contains(axis)) continue;
            Object[] at = firstSeen.get(axis);
            SheetDef sd = (SheetDef) at[0];
            ParsedRow row = (ParsedRow) at[1];
            ColumnDef col = (ColumnDef) at[2];
            out.add(new DsValidationError(sd.sheetName, row.excelRow(), col.label,
                    DatasetValidationReasons.AXIS_NOT_REGISTERED));
        }
        return out;
    }

    /**
     * 需要做存在性校验的主数据类型；不需要则返回 null。
     *
     * <p>开关是 Registry 的 {@code masterCheck}（B-3 由 {@code .master(...)} 置位、
     * {@code .masterNoCheck(...)} 不置位）—— 后者用于编码域多态的列（如「投入料号」既可能是材质也可能是零件），
     * 强校验会把合法文件整份拒收。
     */
    private String masterTypeOf(ColumnDef c) {
        if (!c.masterCheck || c.dropdown == null) return null;
        String mt = c.dropdown.masterType;
        return masters.supports(mt) ? mt : null;
    }

    /**
     * 主数据缺失的 reason。客户编号单独一档（D-19 / AC-46）：
     * 文案 {@code 客户编号未在客户档案中登记}，让用户直接看出「要先去客户管理建档」。
     * 两个文案都在 api.md §1 的封闭集内。
     */
    private String masterReason(String masterType) {
        return "customer".equals(masterType)
                ? DatasetValidationReasons.CUSTOMER_NOT_REGISTERED
                : DatasetValidationReasons.MASTER_MISSING;
    }

    private DsValidationError err(SheetDef spec, ParsedRow row, ColumnDef col, String reason) {
        return new DsValidationError(spec.sheetName, row.excelRow(), col.label, reason);
    }
}
