package com.cpq.importexcel.service;

import com.cpq.basicdata.entity.BasicDataAttribute;
import com.cpq.basicdata.entity.BasicDataConfig;
import com.cpq.common.exception.BusinessException;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.importexcel.dto.BasicDataDiffDTO;
import com.cpq.importexcel.dto.ConflictFieldDTO;
import com.cpq.importexcel.dto.CustomerDataConflictDTO;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.importexcel.dto.ResolutionDTO;
import com.cpq.importexcel.dto.ValidationResult;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.importexcel.parser.StreamingExcelParser;
import com.cpq.system.config.service.SystemConfigService;
import com.cpq.system.lock.dto.AcquireLocksResult;
import com.cpq.system.lock.service.ProductImportLockService;
import com.cpq.versioning.VersionedWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BasicDataImportServiceV5 — v5.1 基础资料导入服务（新建，旧版 BasicDataImportService 保留）。
 *
 * <p>职责：
 *   1. 流式 POI SAX 解析（≤2000 行硬限制）
 *   2. 读 system_config 阈值
 *   3. 调用 ProductImportLockService.acquireLocks（自适应粒度）
 *   4. 跑 BV-01~BV-32 业务校验
 *   5. 写 14 张物理表（按依赖顺序）
 *   6. 写 basic_data_change_log（REQUIRES_NEW 子方法）
 *   7. 释放锁（finally 块，REQUIRES_NEW）
 *
 * <p>事务边界（v5.1 §3.7 TECH-7）：
 *   主方法 @Transactional(REQUIRED) — 全有全无
 *   审计日志写入 REQUIRES_NEW — 独立提交
 *   锁释放 REQUIRES_NEW — 独立提交（ProductImportLockService.releaseByImportRecord）
 *
 * <p>X.4 已知限制：
 *   - exchange_rate / customer_tax 两表只写不读（与 v5.1 §3.2 公式契约偏离，待 X.6 校准）
 *   - element_price 层 BV-20~22 v1 跳过（标 TODO）
 *   - 流式解析当前基于固定 Sheet 名称约定（见 SHEET_* 常量），生产需与 BasicDataConfig 元数据对齐
 */
@ApplicationScoped
public class BasicDataImportServiceV5 {

    private static final Logger LOG = Logger.getLogger(BasicDataImportServiceV5.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── V58: 元数据缓存（sheet_name → BasicDataConfig，含 target_table + discriminator）
    // V94: 同名 sheet 可同时有 QUOTATION 和 COSTING 两份配置 (列布局/target 不同),
    //      改为 Map<sheetName, Map<templateKind, BasicDataConfig>>; 选择由 parseExcel(templateKind) 决定
    // ConcurrentHashMap 保证并发安全；@PostConstruct 在应用启动后加载
    private final Map<String, Map<String, BasicDataConfig>> sheetConfigCache = new ConcurrentHashMap<>();
    // key = configId, value = 该 sheet 的属性列表（sorted by sort_order）
    private final Map<UUID, List<BasicDataAttribute>> sheetAttributeCache = new ConcurrentHashMap<>();

    @Inject
    SystemConfigService configService;

    @Inject
    ProductImportLockService lockService;

    @Inject
    EntityManager em;

    @Inject
    FieldMetaCache fieldMetaCache;

    @Inject
    VersionedWriter versionedWriter;

    /** 导入完成后清空 expand-driver 进程级缓存，让新数据立即可见 */
    @Inject
    ComponentDriverService componentDriverService;

    /** B1: 料号版本管理服务 — 升版后写 mat_part_version_log + bump current_version */
    @Inject
    com.cpq.partversion.PartVersionService partVersionService;

    /**
     * CDI self-reference — needed so that @Transactional methods called from non-transactional
     * methods within the same bean go through the CDI proxy (transaction interceptor is applied).
     * Direct `this.doImportInTx(...)` calls would bypass the proxy and skip transaction demarcation.
     */
    @Inject
    Instance<BasicDataImportServiceV5> self;

    // ── V58: 元数据缓存加载 ───────────────────────────────────────────────

    /**
     * 应用启动后加载元数据缓存。
     * 仅加载 ACTIVE 且 target_table NOT NULL 的配置（target_table=NULL 的 sheet 跳过）。
     * 每个 config 对应的 ACTIVE 属性列表也一并加载。
     *
     * <p>注意：此方法在 Quarkus 启动时（CDI bean 初始化后）调用。
     * 若 DB 中无数据（如测试环境 Flyway 尚未执行），缓存为空，parseExcel 将跳过所有 sheet。
     */
    @PostConstruct
    @Transactional
    public void loadConfigCache() {
        try {
            sheetConfigCache.clear();
            sheetAttributeCache.clear();

            @SuppressWarnings("unchecked")
            List<BasicDataConfig> configs = BasicDataConfig
                    .find("status = 'ACTIVE' AND targetTable IS NOT NULL ORDER BY sortOrder")
                    .list();

            for (BasicDataConfig cfg : configs) {
                // V94: 按 (sheetName, templateKind) 双键存放; 同名 sheet 可有 QUOTATION + COSTING + BOTH 多份
                String kind = cfg.templateKind != null ? cfg.templateKind : "BOTH";
                sheetConfigCache.computeIfAbsent(cfg.sheetName, k -> new ConcurrentHashMap<>())
                        .put(kind, cfg);
                @SuppressWarnings("unchecked")
                List<BasicDataAttribute> attrs = BasicDataAttribute
                        .find("configId = ?1 AND status = 'ACTIVE' ORDER BY sortOrder", cfg.id)
                        .list();
                sheetAttributeCache.put(cfg.id, attrs);
            }

            LOG.infof("V58 metadata cache loaded: %d active sheets with target_table", sheetConfigCache.size());
        } catch (Exception e) {
            LOG.warnf("V58 metadata cache load failed (DB not ready?): %s", e.getMessage());
        }
    }

    /**
     * 重新加载元数据缓存（供运行时配置更新后刷新，如 POST /api/cpq/basic-data-config/reload）。
     */
    @Transactional
    public void reloadConfigCache() {
        loadConfigCache();
    }

    // ── 主入口（preview 模式） ────────────────────────────────────────────

    /**
     * 预览：解析 + 校验，不写库。
     * 无 @Transactional（只读操作），校验结果返回前端供用户确认。
     * 校验通过（hasErrors=false）时额外检测 basicDataDiffs 和 customerDataConflicts。
     */
    public ImportResultDTO previewV5(InputStream excelStream, UUID customerId, UUID userId) {
        return previewV5(excelStream, customerId, userId, "QUOTATION");
    }

    /**
     * V94 重载: 加 templateKind ('QUOTATION'/'COSTING') 决定 sheet 配置选择。
     */
    public ImportResultDTO previewV5(InputStream excelStream, UUID customerId, UUID userId, String templateKind) {
        int maxRows = (int) configService.getNumber("validation.import_max_rows");

        // 1. 解析 Excel
        ParsedBasicData data = parseExcel(excelStream, customerId, maxRows, templateKind);

        // 2. 校验（不写库）
        ValidationResult vr = runAllValidations(data, customerId, false);

        ImportResultDTO result = new ImportResultDTO();
        result.status = vr.hasErrors ? "PREVIEW_BLOCKED" : "PREVIEW_OK";
        result.totalRows = data.totalRows;
        result.processedRows = data.totalRows;
        result.validation = vr;

        // 3. 仅 hasErrors=false 时做差异/冲突检测 + 孤儿行检测
        if (!vr.hasErrors) {
            result.basicDataDiffs = detectBasicDataDiffs(data, customerId);
            result.customerDataConflicts = detectCustomerDataConflicts(data, customerId);
            result.orphanRows = detectOrphanRows(data, customerId);
            // B1: 料号版本预览 — 为每个 (cpn, hf) 提供 currentVersion + 建议 newVersion
            result.partVersionPreview = detectPartVersionPreview(data, customerId);
        }

        return result;
    }

    // ── 主入口（confirm 写入模式） ────────────────────────────────────────

    /**
     * 真正写入（非事务入口）：先单独提交锁，再在独立事务中执行导入，最后释放锁。
     *
     * <p>事务分层（v5.1 §3.7 TECH-7 + 锁可见性修正）：
     * <ol>
     *   <li>acquireLocks — @Transactional(REQUIRED)，无外部事务时立即提交，锁行写入 DB</li>
     *   <li>self.get().doImportInTx — @Transactional(REQUIRED)，全有全无主事务，提交后锁可被 REQUIRES_NEW 看见</li>
     *   <li>releaseByImportRecord — @Transactional(REQUIRES_NEW)，在步骤 2 提交后执行，能正常读到锁行</li>
     * </ol>
     *
     * <p>关键：此方法本身 <b>无 @Transactional</b>。若直接加 @Transactional，acquireLocks 会加入外部事务，
     * 其插入的锁行在外部事务提交前对 REQUIRES_NEW 不可见，导致释放失败。
     */
    /**
     * 确认写入（不带 resolutions，向后兼容入口）。
     */
    public ImportResultDTO importBasicDataV5(InputStream excelStream, UUID customerId, UUID userId) {
        return importBasicDataV5(excelStream, customerId, userId, Collections.emptyList());
    }

    /**
     * 确认写入（带 resolutions 决策）。
     * resolutions 为空时行为与旧版完全一致。
     */
    public ImportResultDTO importBasicDataV5(InputStream excelStream, UUID customerId, UUID userId,
                                              List<ResolutionDTO> resolutions) {
        return importBasicDataV5(excelStream, customerId, userId, resolutions, "QUOTATION");
    }

    /**
     * B1 重载: 加 partVersionDecisions 接收用户料号版本决策.
     *
     * @param partVersionDecisions Map, key="cpn|hf", value="BUMP"/"NO_CHANGE"/"SKIP".
     *                             null 或空 = 全部不升版 (与旧 4 参重载等价).
     *                             B1.5 阶段才接入 INSERT 参数化, 此时升版只 bump mapping
     *                             + 写 log, 实际数据行 part_version 仍 = 2000.
     */
    public ImportResultDTO importBasicDataV5(InputStream excelStream, UUID customerId, UUID userId,
                                              List<ResolutionDTO> resolutions, String templateKind,
                                              java.util.Map<String, String> partVersionDecisions) {
        ImportResultDTO result = importBasicDataV5(excelStream, customerId, userId, resolutions, templateKind);

        // B1.6: 导入成功后处理 BUMP 决策 — 写 mat_part_version_log + bump current_version
        if ("SUCCESS".equals(result.status) && partVersionDecisions != null && !partVersionDecisions.isEmpty()) {
            for (var entry : partVersionDecisions.entrySet()) {
                String key = entry.getKey();
                String action = entry.getValue();
                if (!"BUMP".equals(action)) continue;  // 仅 BUMP 触发升版
                int sep = key.indexOf('|');
                if (sep < 0) {
                    LOG.warnf("partVersionDecisions key 格式错误 (应为 'cpn|hf'): %s", key);
                    continue;
                }
                String cpn = key.substring(0, sep);
                String hf = key.substring(sep + 1);
                try {
                    int newVer = partVersionService.applyVersionBump(
                            cpn, hf, userId,
                            "import-V5-bump",
                            null,  // contentHash B1 不算指纹
                            null   // diffSummary B1 不传 diff
                    );
                    LOG.infof("B1: (%s, %s) version bumped to v%d", cpn, hf, newVer);
                } catch (Exception e) {
                    LOG.warnf("B1: bump version failed for (%s, %s): %s", cpn, hf, e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * V94 重载: 加 templateKind ('QUOTATION'/'COSTING') 决定 sheet 配置选择。
     */
    public ImportResultDTO importBasicDataV5(InputStream excelStream, UUID customerId, UUID userId,
                                              List<ResolutionDTO> resolutions, String templateKind) {
        int maxRows = (int) configService.getNumber("validation.import_max_rows");

        // 1. 流式解析（无事务，纯内存操作）
        ParsedBasicData data = parseExcel(excelStream, customerId, maxRows, templateKind);

        UUID importRecordId = UUID.randomUUID();

        // 2. 获取产品级悲观锁（@Transactional(REQUIRED)，在此 non-tx 上下文中自行提交）
        List<String> partNos = extractPartNos(data);
        lockService.acquireLocks(customerId, partNos, userId, importRecordId);

        try {
            // 3. 在独立 @Transactional 中执行全有全无导入（通过 CDI proxy 确保事务边界生效）
            ImportResultDTO result = self.get().doImportInTx(data, customerId, userId, importRecordId,
                    resolutions != null ? resolutions : Collections.emptyList());

            // 4. 主事务已提交，清空进程级缓存，让新导入数据立即可见
            //    仅在 SUCCESS 路径执行（doImportInTx 抛异常时跳过，缓存保留旧数据无害）
            try {
                componentDriverService.evictAll();
            } catch (Exception e) {
                LOG.warnf("evictAll expand-driver cache failed (non-fatal): %s", e.getMessage());
            }
            try {
                com.cpq.formula.resource.FormulaEvalCache.evictAll();
            } catch (Exception e) {
                LOG.warnf("evictAll formula-eval cache failed (non-fatal): %s", e.getMessage());
            }

            return result;
        } finally {
            // 5. 释放锁（REQUIRES_NEW，此时步骤 3 事务已提交/回滚，锁行可见）
            try {
                lockService.releaseByImportRecord(importRecordId, userId);
            } catch (Exception e) {
                LOG.warnf("Failed to release locks for record %s: %s", importRecordId, e.getMessage());
            }
        }
    }

    /**
     * 导入主事务体（@Transactional(REQUIRED)）——全有全无。
     * 向后兼容重载（无 resolutions）。
     *
     * <p>必须通过 CDI proxy 调用（使用 self.get().doImportInTx），不能直接 this.doImportInTx()。
     */
    @Transactional
    public ImportResultDTO doImportInTx(ParsedBasicData data, UUID customerId, UUID userId, UUID importRecordId) {
        return doImportInTx(data, customerId, userId, importRecordId, Collections.emptyList());
    }

    /**
     * 导入主事务体（@Transactional(REQUIRED)）——全有全无，带 resolutions。
     *
     * <p>必须通过 CDI proxy 调用（使用 self.get().doImportInTx），不能直接 this.doImportInTx()。
     */
    @Transactional
    public ImportResultDTO doImportInTx(ParsedBasicData data, UUID customerId, UUID userId,
                                         UUID importRecordId, List<ResolutionDTO> resolutions) {
        try {
            // BV-01~BV-32 校验（写入模式，查库做跨表校验）
            ValidationResult vr = runAllValidations(data, customerId, true);
            if (vr.hasErrors) {
                // 阻塞级错误：收集后一次性返回，不写库，写 FAILED ImportRecord
                String errJson = serializeErrors(vr);
                writeImportRecordInNewTx(importRecordId, customerId, userId, "FAILED",
                        data.totalRows, 0, errJson);
                ImportResultDTO result = new ImportResultDTO();
                result.importRecordId = importRecordId;
                result.status = "FAILED";
                result.totalRows = data.totalRows;
                result.processedRows = 0;
                result.validation = vr;
                result.errorSummary = "校验失败，共 " + vr.errors.size() + " 个阻塞错误";
                return result;
            }

            // ── Resolution 处理步骤（writePhysicalTables 之前）──────────────
            if (resolutions != null && !resolutions.isEmpty()) {
                // R-1: 乐观锁校验（ACCEPT_NEW 的旧值是否在 preview→confirm 期间被修改）
                validateOldValuesOrThrow409(resolutions);

                // R-2: CRITICAL 字段 ACCEPT_NEW 必须有 note
                validateCriticalNotes(resolutions);

                // R-3: 将 KEEP_OLD 决策标记到 data 中
                applyResolutionsToParsedData(data, resolutions);

                // R-4: 执行 DELETE_ORPHAN 孤儿行删除（在主事务内）
                deleteOrphans(resolutions, customerId);
            }

            // 写 14 张物理表（按依赖顺序）
            ImportResultDTO stats = writePhysicalTables(data, customerId, userId, importRecordId, resolutions);

            // 写 ImportRecord（SUCCESS 状态，主事务内，含 resolutions metadata）
            String metadataJson = serializeResolutions(resolutions);
            writeImportRecord(importRecordId, customerId, userId, "SUCCESS",
                    data.totalRows, data.totalRows, null, metadataJson);

            // 审计变更日志（REQUIRES_NEW 独立事务）
            writeChangeLogs(data, customerId, userId, importRecordId, stats);

            stats.importRecordId = importRecordId;
            stats.status = "SUCCESS";
            stats.totalRows = data.totalRows;
            stats.processedRows = data.totalRows;
            stats.validation = vr;

            LOG.infof("V5 import completed: customer=%s record=%s rows=%d resolutions=%d",
                    customerId, importRecordId, data.totalRows,
                    resolutions != null ? resolutions.size() : 0);
            return stats;

        } catch (BusinessException be) {
            // 业务异常：主事务回滚，用 REQUIRES_NEW 写 FAILED 记录
            writeImportRecordInNewTx(importRecordId, customerId, userId, "FAILED",
                    0, 0, "{\"error\":\"" + escapeJson(be.getMessage()) + "\"}");
            throw be;
        } catch (Exception e) {
            writeImportRecordInNewTx(importRecordId, customerId, userId, "FAILED",
                    0, 0, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            throw new BusinessException(500, "导入失败: " + e.getMessage());
        }
    }

    // ── Excel 流式解析（V58 元数据驱动，删除 SHEET_* 硬编码）─────────────────

    /**
     * V58 元数据驱动解析。
     * 遍历 sheetConfigCache（ACTIVE + target_table NOT NULL 的 sheet 配置），
     * 按 column_letter 读列（不依赖 Excel 表头名称），
     * 按 target_table 分发到对应的 ParsedBasicData 列表。
     *
     * <p>PM 决策 7：target_table=NULL 的 sheet → 跳过 + WARN 日志（由 loadConfigCache 已过滤，不入缓存）。
     * <p>PM 决策 5：is_required=true 的属性为空 → ValidationResult.errors（在 runAllValidations 之前由调用方追加）。
     */
    public ParsedBasicData parseExcel(InputStream excelStream, UUID customerId, int maxRows) {
        return parseExcel(excelStream, customerId, maxRows, "QUOTATION");
    }

    /**
     * V94 重载: 加 templateKind 参数 (QUOTATION / COSTING)。
     * 同名 sheet 在 cache 里可以有 QUOTATION + COSTING + BOTH 多份配置, 按调用方 templateKind 选择:
     *   优先匹配请求的 kind, 其次匹配 BOTH 兜底; 都没有则跳过该 sheet。
     */
    public ParsedBasicData parseExcel(InputStream excelStream, UUID customerId, int maxRows, String templateKind) {
        if (templateKind == null || templateKind.isBlank()) templateKind = "QUOTATION";
        final String requestKind = templateKind;
        StreamingExcelParser parser = new StreamingExcelParser();
        ParsedBasicData data = new ParsedBasicData();

        // 读取 Excel 字节（需多次解析不同 sheet）
        byte[] excelBytes;
        try {
            excelBytes = excelStream.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(400, "读取 Excel 文件失败: " + e.getMessage());
        }

        // 先列出 Excel 中实际存在的 sheet 名
        Set<String> excelSheetNames;
        try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
            excelSheetNames = new HashSet<>(parser.listSheetNames(is));
        } catch (Exception e) {
            throw new BusinessException(400, "无法读取 Excel sheet 列表: " + e.getMessage());
        }

        int totalRows = 0;

        if (sheetConfigCache.isEmpty()) {
            LOG.warn("V58: sheetConfigCache is empty — no metadata configured. " +
                     "Flyway V58_5 may not have run yet. All sheets will be skipped.");
        }

        // V94: 遍历所有已配置的 sheet name; 每个 sheet name 按 templateKind 选对应 config
        for (Map.Entry<String, Map<String, BasicDataConfig>> entry : sheetConfigCache.entrySet()) {
            String sheetName = entry.getKey();
            Map<String, BasicDataConfig> kindMap = entry.getValue();
            // 优先精确匹配 requestKind (QUOTATION / COSTING), 其次匹配 BOTH; 都没有则跳过
            BasicDataConfig cfg = kindMap.get(requestKind);
            if (cfg == null) cfg = kindMap.get("BOTH");
            if (cfg == null) {
                // 此 sheet 不适用于本次导入入口 (例如核价入口收到一个仅 QUOTATION 配置的 sheet)
                continue;
            }

            if (!excelSheetNames.contains(sheetName)) {
                // Excel 中不存在该 sheet，跳过（非错误，PM 决策 7 的逆向：有配置但 Excel 没有该 sheet）
                continue;
            }

            List<BasicDataAttribute> attrs = sheetAttributeCache.getOrDefault(cfg.id, Collections.emptyList());
            if (attrs.isEmpty()) {
                LOG.warnf("V58: sheet '%s' has target_table='%s' but no ACTIVE attributes configured. Skipping.",
                        sheetName, cfg.targetTable);
                continue;
            }

            // 解析 discriminator（目标表固定字段，如 bom_type=INCOMING）
            Map<String, String> discriminator = parseDiscriminator(cfg.targetDiscriminator);

            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                // 按 column_letter 读列（headerRowIndex 默认=1，即 0-based 第0行为表头，第1行起为数据）
                // parseSheetByColumnLetter 返回原始行（不依赖表头）
                int headerRowIdx = cfg.headerRowIndex != null ? cfg.headerRowIndex - 1 : 0;
                List<List<String>> rows = parser.parseSheet(is, sheetName, maxRows, headerRowIdx);

                int dataStartRow = cfg.dataStartRowIndex != null ? cfg.dataStartRowIndex : 2;

                for (int i = 0; i < rows.size(); i++) {
                    List<String> row = rows.get(i);
                    int rowNum = dataStartRow + i;  // 1-based sheet 行号

                    // V90/V92: 跳过空行 + 中文备注行(v4 Excel 末尾常有"因为...需要手动选取..."的说明文字)
                    // 规则:
                    //   1) 所有 attribute 列都空 → 真空行, skip
                    //   2) 任一 IDENTIFIER 列非空且不含 CJK 中文(一-鿿) → 真数据行, 不 skip
                    //   3) IDENTIFIER 列要么全空、要么仅含 CJK 中文 → 视为备注行, skip
                    // 这能区分 R6(全空) / R7(column A 是中文备注) vs 真数据行(料号 = 数字字符串)
                    boolean hasAnyContent = false;
                    boolean hasValidIdentifier = false;
                    for (BasicDataAttribute attr : attrs) {
                        String v = getByColumnLetter(row, attr.columnLetter);
                        if (v == null || v.isBlank()) continue;
                        hasAnyContent = true;
                        if ("IDENTIFIER".equals(attr.dataType)) {
                            boolean hasCJK = v.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
                            if (!hasCJK) {
                                hasValidIdentifier = true;
                                break; // 找到合法 ID, 此行是真数据
                            }
                        }
                    }
                    if (!hasAnyContent) {
                        // 整行空 → Excel 尾随空行, 静默 skip
                        continue;
                    }
                    if (!hasValidIdentifier) {
                        // 整行有内容但无合法标识 (IDENTIFIER 全为空 或 只含中文备注) → 视为备注行
                        LOG.debugf("V92: skip 备注/无效标识行 sheet=%s rowNum=%d", sheetName, rowNum);
                        continue;
                    }

                    // 按 column_letter 构建属性 key→value map
                    Map<String, String> colValues = new LinkedHashMap<>();
                    for (BasicDataAttribute attr : attrs) {
                        String val = getByColumnLetter(row, attr.columnLetter);
                        colValues.put(attr.variableCode, val);

                        // PM 决策 5：is_required=true 且为空 → 收集 required 错误（在 parseExcel 返回前批量报告）
                        // 注：此处仅标记到 data 中，runAllValidations 阶段统一处理
                        // 为简化，在此直接加到 data 的 requiredErrors（新增字段）
                        if (Boolean.TRUE.equals(attr.isRequired) && (val == null || val.isBlank())) {
                            data.addRequiredError("BV-META-01", rowNum, sheetName,
                                    "列 " + attr.columnLetter + "（" + attr.columnTitle + "/" +
                                    attr.variableCode + "）为必填，第 " + rowNum + " 行为空");
                        }
                    }

                    // 按 target_table 分发到对应 ParsedBasicData 列表
                    switch (cfg.targetTable) {
                        case "mat_part" -> fillMatPartRow(data, colValues, discriminator, rowNum, customerId);
                        case "mat_bom"  -> fillMatBomRow(data, colValues, discriminator, rowNum, customerId);
                        case "mat_process" -> fillMatProcessRow(data, colValues, discriminator, rowNum, customerId);
                        // V125: plating_plan / mat_plating_plan 共用 fill, 用 targetTable 区分写入目标
                        case "plating_plan", "mat_plating_plan"
                            -> fillPlatingPlanRow(data, colValues, discriminator, rowNum, customerId, cfg.targetTable);
                        case "mat_fee"  -> fillMatFeeRow(data, colValues, discriminator, rowNum, customerId);
                        // V125: plating_fee / mat_plating_fee 共用 fill, 用 targetTable 区分写入目标
                        case "plating_fee", "mat_plating_fee"
                            -> fillPlatingFeeRow(data, colValues, discriminator, rowNum, customerId, cfg.targetTable);
                        case "mat_customer_part_mapping" -> fillMappingRow(data, colValues, discriminator, rowNum, customerId);
                        // V90: 8 张 costing_part_* 表统一走通用容器, 由 writeCostingPartRows 分发到 UPSERT
                        // V125: 加 costing_part_plating_fee (核价侧电镀费用)
                        case "costing_part_process_cost",
                             "costing_part_tooling_cost",
                             "costing_part_material_bom",
                             "costing_part_element_bom",
                             "costing_part_quality_check",
                             "costing_part_plating",
                             "costing_part_plating_fee",
                             "costing_part_design_cost",
                             "costing_part_weight"
                            -> fillCostingPartRow(data, colValues, discriminator, rowNum, cfg.targetTable);
                        default -> LOG.warnf("V58: unknown target_table '%s' for sheet '%s', skipping row %d",
                                cfg.targetTable, sheetName, rowNum);
                    }
                }
                totalRows += rows.size();
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Sheet '%s' parse error: %s", sheetName, e.getMessage());
            }
        }

        // 缓存为空时（DB 还没有 V58_5 seed），回退到旧的硬编码解析保证旧测试不退化
        // PM 决策 6 要求完全元数据化，但若缓存为空则说明环境未就绪，以 WARN 形式提醒
        if (sheetConfigCache.isEmpty()) {
            LOG.warn("V58: Falling back to legacy header-based parsing because sheetConfigCache is empty.");
            return parseExcelLegacy(excelBytes, customerId, maxRows, parser);
        }

        data.totalRows = totalRows;
        return data;
    }

    // ── V58 元数据驱动行填充方法 ────────────────────────────────────────────

    private void fillMatPartRow(ParsedBasicData data, Map<String, String> cv,
                                 Map<String, String> disc, int rowNum, UUID customerId) {
        String partNo = cv.get("part_no");
        if (partNo == null || partNo.isBlank()) return;
        ParsedBasicData.MatPartRow r = new ParsedBasicData.MatPartRow();
        r.rowNum = rowNum;
        r.partNo = partNo;
        r.partName = cv.get("part_name");
        r.specification = cv.get("specification");
        r.sizeInfo = cv.get("size_info");
        r.unitWeight = toDecimal(cv.get("unit_weight"));
        r.weightUnit = cv.get("weight_unit");
        String sc = cv.get("status_code");
        r.statusCode = (sc != null && !sc.isBlank()) ? sc : "Y";
        data.matParts.add(r);
    }

    private void fillMatBomRow(ParsedBasicData data, Map<String, String> cv,
                                Map<String, String> disc, int rowNum, UUID customerId) {
        String hfPartNo = cv.get("hf_part_no");
        if (hfPartNo == null || hfPartNo.isBlank()) return;
        ParsedBasicData.MatBomRow r = new ParsedBasicData.MatBomRow();
        r.rowNum = rowNum;
        r.hfPartNo = hfPartNo;
        // discriminator 优先（来料BOM/元素BOM），否则从列读取 bom_type
        r.bomType = disc.getOrDefault("bom_type", cv.get("bom_type"));
        Integer seqNo = toInt(cv.get("seq_no"));
        r.seqNo = seqNo != null ? seqNo : 0;
        r.inputMaterialNo = cv.get("input_material_no");
        r.inputMaterialName = cv.get("input_material_name");
        r.lossRate = toDecimalPercent(cv.get("loss_rate"));
        r.grossQty = toDecimal(cv.get("gross_qty"));
        r.netQty = toDecimal(cv.get("net_qty"));
        r.grossUnit = cv.get("gross_unit");
        r.netUnit = cv.get("net_unit");
        r.outputMaterialType = cv.get("output_material_type");
        r.defectRate = toDecimalPercent(cv.get("defect_rate"));
        r.elementName = cv.get("element_name");
        // composition_pct 保留 100 制(BV-01 按 100 比较,与物理表语义一致)
        r.compositionPct = toDecimal(cv.get("composition_pct"));
        data.matBoms.add(r);
    }

    private void fillMatProcessRow(ParsedBasicData data, Map<String, String> cv,
                                    Map<String, String> disc, int rowNum, UUID customerId) {
        String hfPartNo = cv.get("hf_part_no");
        if (hfPartNo == null || hfPartNo.isBlank()) return;
        // V130 防御: mat_process 行必须至少有 assembly_process 或 component_name 之一,
        // 否则视为"非组成件"脏行（可能 sheet 配置错把 fee 类 sheet 路由到 mat_process）.
        String assemblyProcess = cv.get("assembly_process");
        String componentName = cv.get("component_name");
        if ((assemblyProcess == null || assemblyProcess.isBlank())
                && (componentName == null || componentName.isBlank())) {
            LOG.warnf("V130: skip mat_process row (no assembly_process & no component_name) — likely misrouted from fee sheet. partNo=%s rowNum=%d cv=%s",
                      hfPartNo, rowNum, cv);
            return;
        }
        ParsedBasicData.MatProcessRow r = new ParsedBasicData.MatProcessRow();
        r.rowNum = rowNum;
        r.customerId = customerId;
        r.hfPartNo = hfPartNo;
        Integer seqNo = toInt(cv.get("seq_no"));
        r.seqNo = seqNo != null ? seqNo : 0;
        r.subSeqNo = toInt(cv.get("sub_seq_no"));
        r.processCode = cv.get("process_code");
        r.assemblyProcess = cv.get("assembly_process");
        r.componentPartNo = cv.get("component_part_no");
        r.componentName = cv.get("component_name");
        r.supplierCode = cv.get("supplier_code");
        r.supplierName = cv.get("supplier_name");
        r.quantity = toDecimal(cv.get("quantity"));
        r.quantityUnit = cv.get("quantity_unit");
        r.unitPrice = toDecimal(cv.get("unit_price"));
        r.freight = toDecimal(cv.get("freight"));
        r.currency = cv.get("currency");
        r.priceUnit = cv.get("price_unit");
        data.matProcesses.add(r);
    }

    private void fillPlatingPlanRow(ParsedBasicData data, Map<String, String> cv,
                                     Map<String, String> disc, int rowNum, UUID customerId,
                                     String targetTable) {
        String planCode = cv.get("plan_code");
        if (planCode == null || planCode.isBlank()) return;
        ParsedBasicData.PlatingPlanRow r = new ParsedBasicData.PlatingPlanRow();
        r.rowNum = rowNum;
        r.targetTable = targetTable;     // V125: plating_plan | mat_plating_plan
        r.planCode = planCode;
        r.version = cv.get("version");
        Integer seqNo = toInt(cv.get("seq_no"));
        r.seqNo = seqNo != null ? seqNo : 0;
        r.platingElement = cv.get("plating_element");
        r.platingArea = toDecimal(cv.get("plating_area"));
        r.coatingThickness = toDecimal(cv.get("coating_thickness"));
        r.platingRequirement = cv.get("plating_requirement");
        data.platingPlans.add(r);
    }

    private void fillMatFeeRow(ParsedBasicData data, Map<String, String> cv,
                                Map<String, String> disc, int rowNum, UUID customerId) {
        String hfPartNo = cv.get("hf_part_no");
        if (hfPartNo == null || hfPartNo.isBlank()) return;
        // V131 防御: FINISHED_OTHER / INCOMING_OTHER 必须有 dim_element_name (要素名称)
        // 否则 UI 多行时无法区分; 早期 import 的 NULL 行已通过 V131 清理
        String feeTypeForCheck = disc.getOrDefault("fee_type", cv.get("fee_type"));
        if ("FINISHED_OTHER".equals(feeTypeForCheck) || "INCOMING_OTHER".equals(feeTypeForCheck)) {
            String elemName = cv.get("dim_element_name");
            if (elemName == null || elemName.isBlank()) {
                LOG.warnf("V131: skip mat_fee row (fee_type=%s but dim_element_name 为空, 视为脏行) partNo=%s rowNum=%d",
                          feeTypeForCheck, hfPartNo, rowNum);
                return;
            }
        }
        ParsedBasicData.MatFeeRow r = new ParsedBasicData.MatFeeRow();
        r.rowNum = rowNum;
        r.customerId = customerId;
        r.hfPartNo = hfPartNo;
        // discriminator 优先（来料固定加工费等），否则从列读取 fee_type
        r.feeType = disc.getOrDefault("fee_type", cv.get("fee_type"));
        Integer seqNo = toInt(cv.get("seq_no"));
        r.seqNo = seqNo != null ? seqNo : 0;
        r.feeValue = toDecimal(cv.get("fee_value"));
        r.feeRatio = toDecimalPercent(cv.get("fee_ratio"));
        r.currency = cv.get("currency");
        r.priceUnit = cv.get("price_unit");
        r.dimInputMaterialNo = cv.get("dim_input_material_no");
        r.dimInputMaterialName = cv.get("dim_input_material_name");
        r.dimElementName = cv.get("dim_element_name");
        r.dimAssemblyProcess = cv.get("dim_assembly_process");
        r.dimSubSeqNo = toInt(cv.get("dim_sub_seq_no"));
        r.priceFloating = parseBool(cv.get("price_floating"));
        r.settlementRiseRatio = toDecimalPercent(cv.get("settlement_rise_ratio"));
        r.fixedRiseValue = toDecimal(cv.get("fixed_rise_value"));
        r.riseCurrency = cv.get("rise_currency");
        r.riseUnit = cv.get("rise_unit");
        r.rejectRate = toDecimalPercent(cv.get("reject_rate"));
        data.matFees.add(r);
    }

    private void fillPlatingFeeRow(ParsedBasicData data, Map<String, String> cv,
                                    Map<String, String> disc, int rowNum, UUID customerId,
                                    String targetTable) {
        String hfPartNo = cv.get("hf_part_no");
        if (hfPartNo == null || hfPartNo.isBlank()) return;
        ParsedBasicData.PlatingFeeRow r = new ParsedBasicData.PlatingFeeRow();
        r.rowNum = rowNum;
        r.targetTable = targetTable;       // V125: plating_fee | mat_plating_fee
        r.customerId = customerId;
        r.hfPartNo = hfPartNo;
        r.platingPlanCode = cv.get("plating_plan_code");
        r.planVersion = cv.get("plan_version");
        r.platingProcessFee = toDecimal(cv.get("plating_process_fee"));
        r.platingMaterialFee = toDecimal(cv.get("plating_material_fee"));
        r.currency = cv.get("currency");
        r.priceUnit = cv.get("price_unit");
        r.defectRate = toDecimalPercent(cv.get("defect_rate"));
        data.platingFees.add(r);
    }

    private void fillMappingRow(ParsedBasicData data, Map<String, String> cv,
                                 Map<String, String> disc, int rowNum, UUID customerId) {
        String cpNo = cv.get("customer_product_no");
        if (cpNo == null || cpNo.isBlank()) return;
        ParsedBasicData.MappingRow r = new ParsedBasicData.MappingRow();
        r.rowNum = rowNum;
        r.customerId = customerId;
        r.customerProductNo = cpNo;
        r.customerPartName = cv.get("customer_part_name");
        r.customerDrawingNo = cv.get("customer_drawing_no");
        r.hfPartNo = cv.get("hf_part_no");
        r.paymentMethod = cv.get("payment_method");
        r.baseCurrency = cv.get("base_currency");
        r.quoteCurrency = cv.get("quote_currency");
        data.mappings.add(r);
    }

    // ── V90: 核价料号级数据通用容器填充 ─────────────────────────────────────

    /**
     * V90 通用 fill 方法: 8 张 costing_part_* 表共用此入口。
     * 不做强类型映射, 把列字典原样保存到 CostingPartRow.values, 由 writeCostingPartRows 阶段
     * 按 targetTable 分发到对应 UPSERT 助手做强类型转换 + INSERT/UPDATE。
     */
    private void fillCostingPartRow(ParsedBasicData data, Map<String, String> cv,
                                     Map<String, String> disc, int rowNum, String targetTable) {
        // 必须有 hf_part_no 或 plating_no(电镀方案/费用) 或 input_material_no(元素 BOM) 作为业务键
        String key = cv.get("hf_part_no");
        if (key == null || key.isBlank()) key = cv.get("plating_no");
        if (key == null || key.isBlank()) key = cv.get("input_material_no");
        if (key == null || key.isBlank()) {
            LOG.warnf("V90: %s row %d 缺少业务键(hf_part_no/plating_no/input_material_no), 跳过",
                    targetTable, rowNum);
            return;
        }
        ParsedBasicData.CostingPartRow r = new ParsedBasicData.CostingPartRow();
        r.rowNum = rowNum;
        r.targetTable = targetTable;
        r.discriminator = (disc != null) ? new java.util.LinkedHashMap<>(disc) : new java.util.LinkedHashMap<>();
        r.values = new java.util.LinkedHashMap<>(cv);
        data.costingPartRows.add(r);
    }

    // ── V58 工具方法 ─────────────────────────────────────────────────────────

    /**
     * 按列字母（A/B/C...AA/AB...）从行中取值，0-based。
     * A=0, B=1, ... Z=25, AA=26, AB=27, ...
     */
    private static String getByColumnLetter(List<String> row, String letter) {
        if (letter == null || letter.isBlank() || row == null) return null;
        int idx = columnLetterToIndex(letter.trim().toUpperCase());
        if (idx < 0 || idx >= row.size()) return null;
        String v = row.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static int columnLetterToIndex(String letter) {
        int idx = 0;
        for (char c : letter.toCharArray()) {
            if (c < 'A' || c > 'Z') break;
            idx = idx * 26 + (c - 'A' + 1);
        }
        return idx - 1;
    }

    private static BigDecimal toDecimal(String v) {
        if (v == null || v.isBlank()) return null;
        try { return new BigDecimal(v.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Excel 模板按整数百分比填写(如 5 = 5%, 25 = 25%),物理表与校验阈值统一存小数制。
     * 此辅助在解析阶段把整数百分比归一为小数: 5 → 0.05, 25 → 0.25。
     * 适用字段: loss_rate / defect_rate / fee_ratio / settlement_rise_ratio /
     *           reject_rate / composition_pct / plating_fee.defect_rate
     */
    private static BigDecimal toDecimalPercent(String v) {
        BigDecimal d = toDecimal(v);
        if (d == null) return null;
        return d.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
    }

    private static Integer toInt(String v) {
        if (v == null || v.isBlank()) return null;
        try { return (int) Double.parseDouble(v.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * 解析 target_discriminator JSONB（如 {"bom_type":"INCOMING"}）为 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseDiscriminator(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            LOG.warnf("V58: failed to parse target_discriminator '%s': %s", json, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── 旧版 header-based 解析（仅作 fallback，供缓存为空时兼容）──────────────

    /**
     * 旧版基于表头名的解析（fallback）。
     * 仅在 sheetConfigCache 为空时（V58_5 尚未执行）使用，保证测试环境不退化。
     */
    private ParsedBasicData parseExcelLegacy(byte[] excelBytes, UUID customerId, int maxRows,
                                              StreamingExcelParser parser) {
        ParsedBasicData data = new ParsedBasicData();
        int totalRows = 0;

        // 解析 mat_part（sheet 名枚举：旧测试 "料号主档"，生产 "单重"）
        for (String sheetName : List.of("料号主档", "单重")) {
            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, sheetName, maxRows, 0);
                if (sd.rows().isEmpty()) continue;
                for (int i = 0; i < sd.rows().size(); i++) {
                    List<String> row = sd.rows().get(i);
                    ParsedBasicData.MatPartRow r = new ParsedBasicData.MatPartRow();
                    r.rowNum = i + 2;
                    r.partNo = sd.get(row, "HF_PART_NO");
                    if (r.partNo == null || r.partNo.isBlank()) continue;
                    r.partName = sd.get(row, "PART_NAME");
                    r.specification = sd.get(row, "SPECIFICATION");
                    r.sizeInfo = sd.get(row, "SIZE_INFO");
                    r.unitWeight = sd.getDecimal(row, "UNIT_WEIGHT");
                    r.weightUnit = sd.get(row, "WEIGHT_UNIT");
                    String sc = sd.get(row, "STATUS_CODE");
                    r.statusCode = (sc != null && !sc.isBlank()) ? sc : "Y";
                    data.matParts.add(r);
                }
                totalRows += sd.rows().size();
                break;  // 找到第一个有数据的 sheet 即停止
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Legacy: sheet '%s' not found: %s", sheetName, e.getMessage());
            }
        }

        // 解析 mat_bom（BOM清单 + 来料BOM + 元素BOM）
        for (String sheetName : List.of("BOM清单", "来料BOM")) {
            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, sheetName, maxRows, 0);
                if (sd.rows().isEmpty()) continue;
                for (int i = 0; i < sd.rows().size(); i++) {
                    List<String> row = sd.rows().get(i);
                    ParsedBasicData.MatBomRow r = new ParsedBasicData.MatBomRow();
                    r.rowNum = i + 2;
                    r.hfPartNo = sd.get(row, "HF_PART_NO");
                    if (r.hfPartNo == null || r.hfPartNo.isBlank()) continue;
                    r.bomType = sd.get(row, "BOM_TYPE");
                    Integer seqNo = sd.getInt(row, "SEQ_NO");
                    r.seqNo = seqNo != null ? seqNo : 0;
                    r.inputMaterialNo = sd.get(row, "INPUT_MATERIAL_NO");
                    r.inputMaterialName = sd.get(row, "INPUT_MATERIAL_NAME");
                    r.lossRate = sd.getDecimal(row, "LOSS_RATE");
                    r.grossQty = sd.getDecimal(row, "GROSS_QTY");
                    r.netQty = sd.getDecimal(row, "NET_QTY");
                    r.grossUnit = sd.get(row, "GROSS_UNIT");
                    r.netUnit = sd.get(row, "NET_UNIT");
                    r.outputMaterialType = sd.get(row, "OUTPUT_MATERIAL_TYPE");
                    r.defectRate = sd.getDecimal(row, "DEFECT_RATE");
                    r.elementName = sd.get(row, "ELEMENT_NAME");
                    r.compositionPct = sd.getDecimal(row, "COMPOSITION_PCT");
                    data.matBoms.add(r);
                }
                totalRows += sd.rows().size();
                break;
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Legacy: sheet '%s' not found: %s", sheetName, e.getMessage());
            }
        }

        // 解析 mat_process
        for (String sheetName : List.of("组成件BOM", "组成件BOM及单价")) {
            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, sheetName, maxRows, 0);
                if (sd.rows().isEmpty()) continue;
                for (int i = 0; i < sd.rows().size(); i++) {
                    List<String> row = sd.rows().get(i);
                    ParsedBasicData.MatProcessRow r = new ParsedBasicData.MatProcessRow();
                    r.rowNum = i + 2;
                    r.customerId = customerId;
                    r.hfPartNo = sd.get(row, "HF_PART_NO");
                    if (r.hfPartNo == null || r.hfPartNo.isBlank()) continue;
                    Integer seqNo = sd.getInt(row, "SEQ_NO");
                    r.seqNo = seqNo != null ? seqNo : 0;
                    r.subSeqNo = sd.getInt(row, "SUB_SEQ_NO");
                    r.processCode = sd.get(row, "PROCESS_CODE");
                    r.assemblyProcess = sd.get(row, "ASSEMBLY_PROCESS");
                    r.componentPartNo = sd.get(row, "COMPONENT_PART_NO");
                    r.componentName = sd.get(row, "COMPONENT_NAME");
                    r.supplierCode = sd.get(row, "SUPPLIER_CODE");
                    r.supplierName = sd.get(row, "SUPPLIER_NAME");
                    r.quantity = sd.getDecimal(row, "QUANTITY");
                    r.quantityUnit = sd.get(row, "QUANTITY_UNIT");
                    r.unitPrice = sd.getDecimal(row, "UNIT_PRICE");
                    r.freight = sd.getDecimal(row, "FREIGHT");
                    r.currency = sd.get(row, "CURRENCY");
                    r.priceUnit = sd.get(row, "PRICE_UNIT");
                    data.matProcesses.add(r);
                }
                totalRows += sd.rows().size();
                break;
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Legacy: sheet '%s' not found: %s", sheetName, e.getMessage());
            }
        }

        // 解析 plating_plan
        try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
            StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, "电镀方案", maxRows, 0);
            for (int i = 0; i < sd.rows().size(); i++) {
                List<String> row = sd.rows().get(i);
                ParsedBasicData.PlatingPlanRow r = new ParsedBasicData.PlatingPlanRow();
                r.rowNum = i + 2;
                r.planCode = sd.get(row, "PLAN_CODE");
                if (r.planCode == null || r.planCode.isBlank()) continue;
                r.version = sd.get(row, "VERSION");
                Integer seqNo = sd.getInt(row, "SEQ_NO");
                r.seqNo = seqNo != null ? seqNo : 0;
                r.platingElement = sd.get(row, "PLATING_ELEMENT");
                r.platingArea = sd.getDecimal(row, "PLATING_AREA");
                r.coatingThickness = sd.getDecimal(row, "COATING_THICKNESS");
                r.platingRequirement = sd.get(row, "PLATING_REQUIREMENT");
                data.platingPlans.add(r);
            }
            totalRows += sd.rows().size();
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            LOG.debugf("Legacy: sheet '电镀方案' not found: %s", e.getMessage());
        }

        // 解析 mat_fee
        for (String sheetName : List.of("费用清单", "来料固定加工费", "来料其他费用")) {
            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, sheetName, maxRows, 0);
                if (sd.rows().isEmpty()) continue;
                for (int i = 0; i < sd.rows().size(); i++) {
                    List<String> row = sd.rows().get(i);
                    ParsedBasicData.MatFeeRow r = new ParsedBasicData.MatFeeRow();
                    r.rowNum = i + 2;
                    r.customerId = customerId;
                    r.hfPartNo = sd.get(row, "HF_PART_NO");
                    if (r.hfPartNo == null || r.hfPartNo.isBlank()) continue;
                    r.feeType = sd.get(row, "FEE_TYPE");
                    Integer seqNo = sd.getInt(row, "SEQ_NO");
                    r.seqNo = seqNo != null ? seqNo : 0;
                    r.feeValue = sd.getDecimal(row, "FEE_VALUE");
                    r.feeRatio = sd.getDecimal(row, "FEE_RATIO");
                    r.currency = sd.get(row, "CURRENCY");
                    r.priceUnit = sd.get(row, "PRICE_UNIT");
                    r.dimInputMaterialNo = sd.get(row, "DIM_INPUT_MATERIAL_NO");
                    r.dimInputMaterialName = sd.get(row, "DIM_INPUT_MATERIAL_NAME");
                    r.dimElementName = sd.get(row, "DIM_ELEMENT_NAME");
                    r.dimAssemblyProcess = sd.get(row, "DIM_ASSEMBLY_PROCESS");
                    r.dimSubSeqNo = sd.getInt(row, "DIM_SUB_SEQ_NO");
                    r.priceFloating = parseBool(sd.get(row, "PRICE_FLOATING"));
                    r.settlementRiseRatio = sd.getDecimal(row, "SETTLEMENT_RISE_RATIO");
                    r.fixedRiseValue = sd.getDecimal(row, "FIXED_RISE_VALUE");
                    r.riseCurrency = sd.get(row, "RISE_CURRENCY");
                    r.riseUnit = sd.get(row, "RISE_UNIT");
                    r.rejectRate = sd.getDecimal(row, "REJECT_RATE");
                    data.matFees.add(r);
                }
                totalRows += sd.rows().size();
                break;
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Legacy: sheet '%s' not found: %s", sheetName, e.getMessage());
            }
        }

        // 解析 plating_fee
        try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
            StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, "电镀费用", maxRows, 0);
            for (int i = 0; i < sd.rows().size(); i++) {
                List<String> row = sd.rows().get(i);
                ParsedBasicData.PlatingFeeRow r = new ParsedBasicData.PlatingFeeRow();
                r.rowNum = i + 2;
                r.customerId = customerId;
                r.hfPartNo = sd.get(row, "HF_PART_NO");
                if (r.hfPartNo == null || r.hfPartNo.isBlank()) continue;
                r.platingPlanCode = sd.get(row, "PLATING_PLAN_CODE");
                r.planVersion = sd.get(row, "PLAN_VERSION");
                r.platingProcessFee = sd.getDecimal(row, "PLATING_PROCESS_FEE");
                r.platingMaterialFee = sd.getDecimal(row, "PLATING_MATERIAL_FEE");
                r.currency = sd.get(row, "CURRENCY");
                r.priceUnit = sd.get(row, "PRICE_UNIT");
                r.defectRate = sd.getDecimal(row, "DEFECT_RATE");
                data.platingFees.add(r);
            }
            totalRows += sd.rows().size();
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            LOG.debugf("Legacy: sheet '电镀费用' not found: %s", e.getMessage());
        }

        // 解析 mat_customer_part_mapping
        for (String sheetName : List.of("客户料号映射", "客户料号与宏丰料号的关系")) {
            try (var is = new java.io.ByteArrayInputStream(excelBytes)) {
                StreamingExcelParser.SheetData sd = parser.parseSheetWithHeader(is, sheetName, maxRows, 0);
                if (sd.rows().isEmpty()) continue;
                for (int i = 0; i < sd.rows().size(); i++) {
                    List<String> row = sd.rows().get(i);
                    ParsedBasicData.MappingRow r = new ParsedBasicData.MappingRow();
                    r.rowNum = i + 2;
                    r.customerId = customerId;
                    r.customerProductNo = sd.get(row, "CUSTOMER_PRODUCT_NO");
                    if (r.customerProductNo == null || r.customerProductNo.isBlank()) continue;
                    r.customerPartName = sd.get(row, "CUSTOMER_PART_NAME");
                    r.customerDrawingNo = sd.get(row, "CUSTOMER_DRAWING_NO");
                    r.hfPartNo = sd.get(row, "HF_PART_NO");
                    r.paymentMethod = sd.get(row, "PAYMENT_METHOD");
                    r.baseCurrency = sd.get(row, "BASE_CURRENCY");
                    r.quoteCurrency = sd.get(row, "QUOTE_CURRENCY");
                    data.mappings.add(r);
                }
                totalRows += sd.rows().size();
                break;
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                LOG.debugf("Legacy: sheet '%s' not found: %s", sheetName, e.getMessage());
            }
        }

        data.totalRows = totalRows;
        return data;
    }

    // ── BV 校验总入口 ─────────────────────────────────────────────────────

    /**
     * 运行所有 BV-01~BV-32 校验。
     *
     * @param data       已解析的数据
     * @param customerId 导入目标客户
     * @param queryDb    是否执行跨表查库校验（preview=false 时 queryDb=false）
     */
    public ValidationResult runAllValidations(ParsedBasicData data, UUID customerId, boolean queryDb) {
        ValidationResult vr = new ValidationResult();

        // V58: 合并元数据必填校验错误（parseExcel 阶段产生，在此统一入 vr）
        for (ParsedBasicData.RequiredError re : data.requiredErrors) {
            vr.addError(re.bvCode, re.rowNum, re.sheetName, re.message);
        }

        // 读配置阈值
        BigDecimal compositionTolerance = new BigDecimal(configService.getString("validation.composition_tolerance"));
        BigDecimal lossRateMax = new BigDecimal(configService.getString("validation.loss_rate_max"));
        BigDecimal defectRateMax = new BigDecimal(configService.getString("validation.defect_rate_max"));
        BigDecimal assemblyRejectMax = new BigDecimal(configService.getString("validation.assembly_reject_rate_max"));
        BigDecimal priceRiseMin = new BigDecimal(configService.getString("validation.price_rise_min"));
        BigDecimal priceRiseMax = new BigDecimal(configService.getString("validation.price_rise_max"));

        Set<String> allowedCurrencies = parseJsonStringSet(configService.getJson("validation.allowed_currencies"));
        Set<String> allowedUnits = parseJsonStringSet(configService.getJson("validation.allowed_units"));

        vr.merge(validateBasicLayer(data, compositionTolerance, lossRateMax, defectRateMax));
        vr.merge(validateCustomerLayer(data, assemblyRejectMax, priceRiseMin, priceRiseMax,
                allowedCurrencies, allowedUnits, queryDb, customerId));
        vr.merge(validateElementLayer(data));  // v1: TODO 仅打桩
        vr.merge(validateCrossTable(data, customerId, queryDb));

        return vr;
    }

    // ── BV-01~BV-06 基础资料层校验 ───────────────────────────────────────

    public ValidationResult validateBasicLayer(ParsedBasicData data,
                                         BigDecimal compositionTolerance,
                                         BigDecimal lossRateMax,
                                         BigDecimal defectRateMax) {
        ValidationResult vr = new ValidationResult();

        // BV-01: 元素 BOM 含量合计 = 100%（±容差）
        // 分组规则: 同一宏丰料号下的同一投入料号(input_material_no/name) 元素含量之和 = 100%
        // composition_pct 保持 100 制(整数百分比),阈值 100 ± (compositionTolerance * 100)
        Map<String, BigDecimal> elementPctSum = new HashMap<>();
        Map<String, List<Integer>> elementRows = new HashMap<>();
        Map<String, String[]> elementGroupLabel = new HashMap<>();
        for (ParsedBasicData.MatBomRow bom : data.matBoms) {
            if (!"ELEMENT".equals(bom.bomType)) continue;
            if (bom.compositionPct == null) continue;
            String key = bom.hfPartNo + "::" + (bom.inputMaterialNo == null ? "" : bom.inputMaterialNo)
                       + "::" + (bom.inputMaterialName == null ? "" : bom.inputMaterialName);
            elementPctSum.merge(key, bom.compositionPct, BigDecimal::add);
            elementRows.computeIfAbsent(key, k -> new ArrayList<>()).add(bom.rowNum);
            elementGroupLabel.putIfAbsent(key, new String[] {
                bom.hfPartNo,
                bom.inputMaterialNo,
                bom.inputMaterialName
            });
        }
        BigDecimal tolPct = compositionTolerance.multiply(BigDecimal.valueOf(100));
        for (Map.Entry<String, BigDecimal> e : elementPctSum.entrySet()) {
            BigDecimal sum = e.getValue();
            BigDecimal diff = sum.subtract(BigDecimal.valueOf(100)).abs();
            if (diff.compareTo(tolPct) > 0) {
                int firstRow = elementRows.get(e.getKey()).get(0);
                String[] label = elementGroupLabel.get(e.getKey());
                String materialDesc = (label[1] != null && !label[1].isBlank() ? label[1] : "")
                        + (label[2] != null && !label[2].isBlank() ? "(" + label[2] + ")" : "");
                vr.addWarning("BV-01", firstRow, sheetNameOf("mat_bom", "ELEMENT"),
                        "料号 " + label[0] +
                        (materialDesc.isBlank() ? "" : " 投入料号 " + materialDesc) +
                        " 元素含量合计 " + sum + "%，超出 100% ±" + tolPct + "% 容差");
            }
        }

        // BV-02: 单重 > 0（阻塞）
        for (ParsedBasicData.MatPartRow part : data.matParts) {
            if (part.unitWeight == null || part.unitWeight.compareTo(BigDecimal.ZERO) <= 0) {
                vr.addError("BV-02", part.rowNum, sheetNameOf("mat_part", null),
                        "料号 " + part.partNo + " 单重(unit_weight)必须 > 0");
            }
        }

        // BV-03: 来料 BOM 损耗率/不良率范围 [0%, 50%]（警告）
        for (ParsedBasicData.MatBomRow bom : data.matBoms) {
            if (!"INCOMING".equals(bom.bomType)) continue;
            if (bom.lossRate != null) {
                if (bom.lossRate.compareTo(BigDecimal.ZERO) < 0 ||
                        bom.lossRate.compareTo(lossRateMax) > 0) {
                    vr.addWarning("BV-03", bom.rowNum, sheetNameOf("mat_bom", "INCOMING"),
                            "料号 " + bom.hfPartNo + " BOM 行 " + bom.seqNo +
                            " 损耗率 " + bom.lossRate + " 超出范围 [0," + lossRateMax + "]");
                }
            }
            if (bom.defectRate != null) {
                if (bom.defectRate.compareTo(BigDecimal.ZERO) < 0 ||
                        bom.defectRate.compareTo(defectRateMax) > 0) {
                    vr.addWarning("BV-03", bom.rowNum, sheetNameOf("mat_bom", "INCOMING"),
                            "料号 " + bom.hfPartNo + " BOM 行 " + bom.seqNo +
                            " 不良率 " + bom.defectRate + " 超出范围 [0," + defectRateMax + "]");
                }
            }
        }

        // BV-04: 来料 BOM 净用量 ≤ 毛用量（阻塞）
        for (ParsedBasicData.MatBomRow bom : data.matBoms) {
            if (!"INCOMING".equals(bom.bomType)) continue;
            if (bom.netQty != null && bom.grossQty != null) {
                if (bom.netQty.compareTo(bom.grossQty) > 0) {
                    vr.addError("BV-04", bom.rowNum, "mat_bom",
                            "料号 " + bom.hfPartNo + " BOM 行 " + bom.seqNo +
                            " 净用量(" + bom.netQty + ") > 毛用量(" + bom.grossQty + ")");
                }
            }
        }

        // BV-05: 电镀方案的镀层厚度 > 0（阻塞）
        for (ParsedBasicData.PlatingPlanRow pp : data.platingPlans) {
            if (pp.coatingThickness == null || pp.coatingThickness.compareTo(BigDecimal.ZERO) <= 0) {
                vr.addError("BV-05", pp.rowNum, "plating_plan",
                        "电镀方案 " + pp.planCode + "/" + pp.version + " 行 " + pp.seqNo +
                        " 镀层厚度(coating_thickness)必须 > 0");
            }
        }

        // BV-06: 客户料号映射唯一（customer_id, customer_product_no）（阻塞）
        // 在 Excel 内检查重复
        Set<String> mappingKeys = new HashSet<>();
        for (ParsedBasicData.MappingRow m : data.mappings) {
            String key = m.customerId + ":" + m.customerProductNo;
            if (!mappingKeys.add(key)) {
                vr.addError("BV-06", m.rowNum, "mat_customer_part_mapping",
                        "客户料号 " + m.customerProductNo + " 在 Excel 中重复，映射必须唯一");
            }
        }

        return vr;
    }

    // ── BV-10~BV-18 客户资料层校验 ───────────────────────────────────────

    public ValidationResult validateCustomerLayer(ParsedBasicData data,
                                            BigDecimal assemblyRejectMax,
                                            BigDecimal priceRiseMin,
                                            BigDecimal priceRiseMax,
                                            Set<String> allowedCurrencies,
                                            Set<String> allowedUnits,
                                            boolean queryDb,
                                            UUID customerId) {
        ValidationResult vr = new ValidationResult();

        // BV-10: 组成件单价 > 0（警告）
        for (ParsedBasicData.MatProcessRow mp : data.matProcesses) {
            if (mp.unitPrice != null && mp.unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                vr.addWarning("BV-10", mp.rowNum, "mat_process",
                        "料号 " + mp.hfPartNo + " 组成件 seq=" + mp.seqNo +
                        " 单价(unit_price) = " + mp.unitPrice + "，建议 > 0");
            }
        }

        // BV-11: 组成件数量 > 0（警告）
        for (ParsedBasicData.MatProcessRow mp : data.matProcesses) {
            if (mp.quantity != null && mp.quantity.compareTo(BigDecimal.ZERO) <= 0) {
                vr.addWarning("BV-11", mp.rowNum, "mat_process",
                        "料号 " + mp.hfPartNo + " 组成件 seq=" + mp.seqNo +
                        " 数量(quantity) = " + mp.quantity + "，建议 > 0");
            }
        }

        // BV-12: 组成件 BOM 序号唯一（customer_id, hf_part_no, version, seq_no, sub_seq_no）（阻塞）
        // v1：version 固定为 1（新增），仅检查 Excel 内重复
        Set<String> processKeys = new HashSet<>();
        for (ParsedBasicData.MatProcessRow mp : data.matProcesses) {
            String key = customerId + ":" + mp.hfPartNo + ":1:" + mp.seqNo + ":" + mp.subSeqNo;
            if (!processKeys.add(key)) {
                vr.addError("BV-12", mp.rowNum, "mat_process",
                        "料号 " + mp.hfPartNo + " 组成件 seq=" + mp.seqNo + "/sub=" + mp.subSeqNo +
                        " 在 Excel 中重复，序号须唯一");
            }
        }

        // BV-13: 涨价比例范围（警告）
        for (ParsedBasicData.MatFeeRow fee : data.matFees) {
            if (!"INCOMING_FIXED".equals(fee.feeType)) continue;
            if (fee.settlementRiseRatio != null) {
                if (fee.settlementRiseRatio.compareTo(priceRiseMin) < 0 ||
                        fee.settlementRiseRatio.compareTo(priceRiseMax) > 0) {
                    vr.addWarning("BV-13", fee.rowNum, sheetNameOf("mat_fee", fee.feeType),
                            "料号 " + fee.hfPartNo + " 费用行 " + fee.seqNo +
                            " 涨价比例 " + fee.settlementRiseRatio +
                            " 超出范围 [" + priceRiseMin + ", " + priceRiseMax + "]");
                }
            }
        }

        // BV-14: 组装报废率/电镀不良率范围 [0%, 30%]（警告）
        for (ParsedBasicData.MatFeeRow fee : data.matFees) {
            if (!"ASSEMBLY_PROCESS".equals(fee.feeType)) continue;
            if (fee.rejectRate != null) {
                if (fee.rejectRate.compareTo(BigDecimal.ZERO) < 0 ||
                        fee.rejectRate.compareTo(assemblyRejectMax) > 0) {
                    vr.addWarning("BV-14", fee.rowNum, sheetNameOf("mat_fee", fee.feeType),
                            "料号 " + fee.hfPartNo + " 组装报废率 " + fee.rejectRate +
                            " 超出范围 [0, " + assemblyRejectMax + "]");
                }
            }
        }
        for (ParsedBasicData.PlatingFeeRow pf : data.platingFees) {
            if (pf.defectRate != null) {
                if (pf.defectRate.compareTo(BigDecimal.ZERO) < 0 ||
                        pf.defectRate.compareTo(assemblyRejectMax) > 0) {
                    vr.addWarning("BV-14", pf.rowNum, sheetNameOf("plating_fee", null),
                            "料号 " + pf.hfPartNo + " 电镀不良率 " + pf.defectRate +
                            " 超出范围 [0, " + assemblyRejectMax + "]");
                }
            }
        }

        // BV-15: 货币代码合法（阻塞）
        checkCurrencyInProcess(data.matProcesses, allowedCurrencies, vr);
        checkCurrencyInFees(data.matFees, allowedCurrencies, vr);
        checkCurrencyInPlatingFees(data.platingFees, allowedCurrencies, vr);

        // BV-16: 单位代码合法（阻塞）
        checkUnitsInProcess(data.matProcesses, allowedUnits, vr);
        checkUnitsInFees(data.matFees, allowedUnits, vr);
        checkUnitsInPlatingFees(data.platingFees, allowedUnits, vr);

        // BV-17: 引用的电镀方案存在（阻塞）
        // 先在 Excel 内检查；queryDb 时再查库
        Set<String> inExcelPlanKeys = new HashSet<>();
        for (ParsedBasicData.PlatingPlanRow pp : data.platingPlans) {
            if (pp.planCode != null && pp.version != null) {
                inExcelPlanKeys.add(pp.planCode + ":" + pp.version);
            }
        }
        for (ParsedBasicData.PlatingFeeRow pf : data.platingFees) {
            if (pf.platingPlanCode == null || pf.planVersion == null) continue;
            String planKey = pf.platingPlanCode + ":" + pf.planVersion;
            boolean existsInExcel = inExcelPlanKeys.contains(planKey);
            boolean existsInDb = false;
            if (queryDb && !existsInExcel) {
                Long cnt = (Long) em.createNativeQuery(
                        "SELECT COUNT(*) FROM plating_plan WHERE plan_code = :pc AND version = :v")
                        .setParameter("pc", pf.platingPlanCode)
                        .setParameter("v", pf.planVersion)
                        .getSingleResult();
                existsInDb = cnt > 0;
            }
            if (!existsInExcel && !existsInDb) {
                vr.addError("BV-17", pf.rowNum, "plating_fee",
                        "电镀费用行引用的电镀方案 " + pf.platingPlanCode + "/" + pf.planVersion + " 不存在");
            }
        }

        // BV-18: 已移除 (2026-05-09)
        // 原校验: mat_process.component_part_no 必须在 mat_part 或本次 Excel「单重」sheet 中存在
        // 移除原因: component_part_no 是开放语义字段 (供应商零件号 / 车间内部码 / 空值都合法),
        //           不是 mat_part 的外键 (V44 line 80: 纯 VARCHAR(64) 无 FK 约束).
        //           真正该做的是 mat_process 业务键唯一性 (BV-12 已覆盖),
        //           而非组件料号外键完整性. 强校验违反业务直觉 (外购零件不需要注册成生产料号).

        return vr;
    }

    // ── BV-20~BV-22 元素价格层校验（v1 打桩，v2 启用）────────────────────

    public ValidationResult validateElementLayer(ParsedBasicData data) {
        ValidationResult vr = new ValidationResult();
        // TODO X.4 校准: BV-20~BV-22 v1 暂不实施（element_price 表 v1 无数据写入）
        // v2 启用时在此实现：source_id/fetch_rule_id 存在性、升水价 ≥ 0、element_name 合法
        return vr;
    }

    // ── BV-30~BV-32 跨表校验 ─────────────────────────────────────────────

    public ValidationResult validateCrossTable(ParsedBasicData data, UUID customerId, boolean queryDb) {
        ValidationResult vr = new ValidationResult();

        // BV-30: 费用行引用的料号在 mat_part 中登记(警告级)
        // V90 起改为 warning 而非 error: 核价基础数据导入时单重 sheet 写到 costing_part_weight,
        // 不写 mat_part, 此校验对核价场景不适用; 报价单导入也不必阻塞(料号可能被外部系统建立)
        Set<String> partNosWithMaster = new HashSet<>();
        for (ParsedBasicData.MatPartRow part : data.matParts) {
            if (part.partNo != null) partNosWithMaster.add(part.partNo);
        }
        if (queryDb) {
            @SuppressWarnings("unchecked")
            List<Object> dbParts = em.createNativeQuery(
                    "SELECT part_no FROM mat_part").getResultList();
            for (Object o : dbParts) {
                if (o != null) partNosWithMaster.add(o.toString());
            }
            // V90: costing_part_weight 也登记为有效料号(核价场景)
            @SuppressWarnings("unchecked")
            List<Object> cpwParts = em.createNativeQuery(
                    "SELECT hf_part_no FROM costing_part_weight").getResultList();
            for (Object o : cpwParts) {
                if (o != null) partNosWithMaster.add(o.toString());
            }
        }
        for (ParsedBasicData.MatFeeRow fee : data.matFees) {
            if (fee.hfPartNo != null && !partNosWithMaster.contains(fee.hfPartNo)) {
                vr.addWarning("BV-30", fee.rowNum, sheetNameOf("mat_fee", fee.feeType),
                        "费用行引用的料号 " + fee.hfPartNo + " 在「单重」sheet 中未登记基础料号(警告)");
            }
        }
        for (ParsedBasicData.PlatingFeeRow pf : data.platingFees) {
            if (pf.hfPartNo != null && !partNosWithMaster.contains(pf.hfPartNo)) {
                vr.addWarning("BV-30", pf.rowNum, sheetNameOf("plating_fee", null),
                        "电镀费用行引用的料号 " + pf.hfPartNo + " 在「单重」sheet 中未登记基础料号(警告)");
            }
        }

        // BV-31: 客户资料的客户必须与导入选择的客户一致（阻塞）
        // customer_id 由 parseExcel 统一注入，此处校验非空
        for (ParsedBasicData.MatProcessRow mp : data.matProcesses) {
            if (mp.customerId != null && !mp.customerId.equals(customerId)) {
                vr.addError("BV-31", mp.rowNum, "mat_process",
                        "组成件行 customer_id=" + mp.customerId + " 与导入指定客户 " + customerId + " 不一致");
            }
        }
        for (ParsedBasicData.MatFeeRow fee : data.matFees) {
            if (fee.customerId != null && !fee.customerId.equals(customerId)) {
                vr.addError("BV-31", fee.rowNum, "mat_fee",
                        "费用行 customer_id=" + fee.customerId + " 与导入指定客户 " + customerId + " 不一致");
            }
        }
        for (ParsedBasicData.PlatingFeeRow pf : data.platingFees) {
            if (pf.customerId != null && !pf.customerId.equals(customerId)) {
                vr.addError("BV-31", pf.rowNum, "plating_fee",
                        "电镀费用行 customer_id=" + pf.customerId + " 与导入指定客户 " + customerId + " 不一致");
            }
        }

        // BV-32: 移除 — 纯电镀件不需要 mat_process,旧规则误判合法业务为悬空引用
        // 真实悬空(plating_fee 引用的 plan_code/version 不存在)由 BV-17 覆盖,本条已冗余

        // BV-COST-CONFLICT (V93): 核价数据轻量冲突检测 — 预扫已存在的 costing_part_* 行,
        // 警告(非阻塞)告诉用户本次上传会覆盖多少行。完整 diff 抽屉(per 字段 KEEP_OLD/ACCEPT_NEW)
        // 留作后续工作; 这里只给行数级提示, 让用户知道不是"全是新增"。
        if (queryDb && data.costingPartRows != null && !data.costingPartRows.isEmpty()) {
            int overwriteCount = countCostingPartConflicts(data.costingPartRows);
            if (overwriteCount > 0) {
                vr.addWarning("BV-COST-CONFLICT", 0, "核价基础数据",
                        "本次导入将覆盖已存在的 " + overwriteCount + " 行核价数据 (按业务键 UPSERT)");
            }
        }

        return vr;
    }

    /**
     * V93: 预扫 costingPartRows 数组, 统计有多少行会触发 ON CONFLICT DO UPDATE (即覆盖现有行)。
     * 按 targetTable 用其各自的 unique key 探测, 不读全表(避免大数据集 OOM)。
     */
    private int countCostingPartConflicts(List<ParsedBasicData.CostingPartRow> rows) {
        int overwriteCount = 0;
        for (ParsedBasicData.CostingPartRow r : rows) {
            try {
                if (existsCostingPartRow(r)) overwriteCount++;
            } catch (Exception e) {
                // 探测失败不影响导入, 仅 warn
                LOG.debugf("V93: conflict probe failed for %s row %d: %s",
                        r.targetTable, r.rowNum, e.getMessage());
            }
        }
        return overwriteCount;
    }

    private boolean existsCostingPartRow(ParsedBasicData.CostingPartRow r) {
        String sql;
        Map<String, Object> params = new LinkedHashMap<>();
        switch (r.targetTable) {
            case "costing_part_process_cost":
                String costType = r.discriminator.getOrDefault("cost_type", r.values.get("cost_type"));
                if (costType == null) return false;
                sql = "SELECT 1 FROM costing_part_process_cost WHERE hf_part_no = ?1 AND process_no = ?2 AND cost_type = ?3";
                params.put("1", r.values.get("hf_part_no"));
                params.put("2", r.values.get("process_no"));
                params.put("3", costType);
                break;
            case "costing_part_tooling_cost":
                Integer ts = toInt(r.values.get("seq_no"));
                sql = "SELECT 1 FROM costing_part_tooling_cost WHERE hf_part_no = ?1 AND process_no = ?2 AND seq_no = ?3";
                params.put("1", r.values.get("hf_part_no"));
                params.put("2", r.values.get("process_no"));
                params.put("3", ts != null ? ts : 1);
                break;
            case "costing_part_material_bom":
                Integer ms = toInt(r.values.get("seq_no"));
                sql = "SELECT 1 FROM costing_part_material_bom WHERE hf_part_no = ?1 AND seq_no = ?2";
                params.put("1", r.values.get("hf_part_no"));
                params.put("2", ms != null ? ms : r.rowNum);
                break;
            case "costing_part_element_bom":
                Integer es = toInt(r.values.get("seq_no"));
                String ec = r.values.get("element_code");
                if (ec == null || ec.isBlank()) return false;
                sql = "SELECT 1 FROM costing_part_element_bom WHERE input_material_no = ?1 AND seq_no = ?2 AND element_code = ?3";
                params.put("1", r.values.get("input_material_no"));
                params.put("2", es != null ? es : 1);
                params.put("3", ec);
                break;
            case "costing_part_weight":
                sql = "SELECT 1 FROM costing_part_weight WHERE hf_part_no = ?1";
                params.put("1", r.values.get("hf_part_no"));
                break;
            case "costing_part_plating":
                Integer ps = toInt(r.values.get("seq_no"));
                String pn = r.values.get("plating_no");
                String pv = r.values.get("version_number");
                if (pn == null || pv == null) return false;
                sql = "SELECT 1 FROM costing_part_plating WHERE plating_no = ?1 AND version_number = ?2 AND seq_no = ?3";
                params.put("1", pn);
                params.put("2", pv);
                params.put("3", ps != null ? ps : 1);
                break;
            case "costing_part_design_cost":
                sql = "SELECT 1 FROM costing_part_design_cost WHERE hf_part_no = ?1 AND design_drawing_no = ?2 AND version_number = ?3";
                params.put("1", r.values.get("hf_part_no"));
                params.put("2", r.values.getOrDefault("design_drawing_no", ""));
                params.put("3", r.values.getOrDefault("version_number", ""));
                break;
            case "costing_part_quality_check":
                String stage = r.discriminator.getOrDefault("stage", r.values.getOrDefault("stage", "INCOMING"));
                Integer qpseq = toInt(r.values.get("primary_seq_no"));
                Integer qseq = toInt(r.values.get("seq_no"));
                sql = "SELECT 1 FROM costing_part_quality_check WHERE hf_part_no = ?1 AND stage = ?2 " +
                      "AND COALESCE(primary_seq_no, -1) = COALESCE(?3, -1) AND seq_no = ?4";
                params.put("1", r.values.get("hf_part_no"));
                params.put("2", stage);
                params.put("3", qpseq);
                params.put("4", qseq != null ? qseq : 1);
                break;
            default:
                return false;
        }
        try {
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            int idx = 1;
            for (Object v : params.values()) {
                q.setParameter(idx++, v);
            }
            q.setMaxResults(1);
            return !q.getResultList().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ── 写 14 张物理表 ────────────────────────────────────────────────────

    @Transactional
    public ImportResultDTO writePhysicalTables(ParsedBasicData data, UUID customerId, UUID userId, UUID importRecordId) {
        return writePhysicalTables(data, customerId, userId, importRecordId, null);
    }

    /**
     * 写 14 张物理表（按依赖顺序）。
     * 三客户级版本化表（mat_process / mat_fee / plating_fee）通过 {@link VersionedWriter} 写入。
     * resolutions 用于提取字段级 note（透传到 change_log）。
     */
    @Transactional
    public ImportResultDTO writePhysicalTables(ParsedBasicData data, UUID customerId, UUID userId,
                                                UUID importRecordId, List<ResolutionDTO> resolutions) {
        ImportResultDTO stats = new ImportResultDTO();
        OffsetDateTime now = OffsetDateTime.now();

        // 依赖顺序：mat_part → mat_bom → plating_plan → mat_customer_part_mapping
        //         → mat_process → mat_fee → plating_fee

        // 1. mat_part（全局表，UPSERT by part_no）
        for (ParsedBasicData.MatPartRow r : data.matParts) {
            // KEEP_OLD: 对被标记跳过的字段传 null，利用 COALESCE(:param, column) 保留旧值
            String rowKey = r.partNo;
            BigDecimal effUnitWeight = data.shouldSkipField("mat_part", rowKey, "unit_weight")
                    ? null : r.unitWeight;
            String effPartName    = data.shouldSkipField("mat_part", rowKey, "part_name")
                    ? null : r.partName;
            String effSpec        = data.shouldSkipField("mat_part", rowKey, "specification")
                    ? null : r.specification;
            String effSize        = data.shouldSkipField("mat_part", rowKey, "size_info")
                    ? null : r.sizeInfo;
            String effWeightUnit  = data.shouldSkipField("mat_part", rowKey, "weight_unit")
                    ? null : r.weightUnit;

            int updated = em.createNativeQuery(
                    "UPDATE mat_part SET " +
                    "  part_name = COALESCE(:name, part_name), " +
                    "  specification = COALESCE(:spec, specification), " +
                    "  size_info = COALESCE(:size, size_info), " +
                    "  unit_weight = COALESCE(:uw, unit_weight), " +
                    "  weight_unit = COALESCE(:wu, weight_unit), " +
                    "  status_code = :sc, " +
                    "  updated_at = :now, updated_by = :uid " +
                    "WHERE part_no = :pn")
                    .setParameter("name", effPartName)
                    .setParameter("spec", effSpec)
                    .setParameter("size", effSize)
                    .setParameter("uw", effUnitWeight)
                    .setParameter("wu", effWeightUnit)
                    .setParameter("sc", r.statusCode)
                    .setParameter("now", now)
                    .setParameter("uid", userId)
                    .setParameter("pn", r.partNo)
                    .executeUpdate();
            if (updated == 0) {
                em.createNativeQuery(
                        "INSERT INTO mat_part(part_no, part_name, specification, size_info, " +
                        "  unit_weight, weight_unit, status_code, created_at, updated_at, created_by, updated_by) " +
                        "VALUES (:pn, :name, :spec, :size, :uw, :wu, :sc, :now, :now, :uid, :uid)")
                        .setParameter("pn", r.partNo)
                        .setParameter("name", effPartName != null ? effPartName : r.partName)
                        .setParameter("spec", effSpec != null ? effSpec : r.specification)
                        .setParameter("size", effSize != null ? effSize : r.sizeInfo)
                        .setParameter("uw", effUnitWeight != null ? effUnitWeight : r.unitWeight)
                        .setParameter("wu", effWeightUnit != null ? effWeightUnit : r.weightUnit)
                        .setParameter("sc", r.statusCode)
                        .setParameter("now", now)
                        .setParameter("uid", userId)
                        .executeUpdate();
                stats.matPartCreated++;
            } else {
                stats.matPartUpdated++;
            }
        }

        // 1.5 已移除：原本会把每条 mat_part.part_no（生产料号，3120012574 这种 HF 内部料号）
        //      自动 INSERT 到 product 表 category='STANDARD'，让产品管理列表显示生产料号。
        //      但用户明确反馈：产品列表只应展示"客户料号"（由 step 4.5 走 mapping.customer_product_no
        //      同步到「默认分类」），生产料号属于内部主数据，不应混入产品列表。
        //      报价单 autoPopulate 走 customerPartCandidates 接口（mat_part + mat_customer_part_mapping
        //      JOIN），不依赖 product 表里的生产料号行，因此可以安全移除本同步。
        //      如要回到旧行为，参考 git 历史中的本位置代码。

        // 2. mat_bom（全局表，UPSERT by bom_type + hf_part_no + seq_no + input/element）
        for (ParsedBasicData.MatBomRow r : data.matBoms) {
            String bomRowKey = r.hfPartNo + ":" + r.bomType + ":" + r.seqNo;
            if (data.shouldSkipRow("mat_bom", bomRowKey)) continue;

            // V129 防御：写入 INCOMING 真实 input_material_no 行前，清理同 (hf_part_no, seq_no, element_name) 的 NULL 占位行。
            // 仅当本次新行的 input_material_no 非空时才删（防止反向覆盖合法的 NULL 行）。
            if ("INCOMING".equals(r.bomType) && r.inputMaterialNo != null && !r.inputMaterialNo.isBlank()) {
                em.createNativeQuery(
                        "DELETE FROM mat_bom WHERE bom_type = 'INCOMING' " +
                        "  AND hf_part_no = :p AND seq_no = :s " +
                        "  AND COALESCE(element_name,'') = COALESCE(:e,'') " +
                        "  AND input_material_no IS NULL")
                        .setParameter("p", r.hfPartNo)
                        .setParameter("s", r.seqNo)
                        .setParameter("e", r.elementName)
                        .executeUpdate();
            }

            BigDecimal effLossRate      = data.shouldSkipField("mat_bom", bomRowKey, "loss_rate")       ? null : r.lossRate;
            BigDecimal effGrossQty      = data.shouldSkipField("mat_bom", bomRowKey, "gross_qty")       ? null : r.grossQty;
            BigDecimal effNetQty        = data.shouldSkipField("mat_bom", bomRowKey, "net_qty")         ? null : r.netQty;
            String     effGrossUnit     = data.shouldSkipField("mat_bom", bomRowKey, "gross_unit")      ? null : r.grossUnit;
            String     effNetUnit       = data.shouldSkipField("mat_bom", bomRowKey, "net_unit")        ? null : r.netUnit;
            String     effImName        = data.shouldSkipField("mat_bom", bomRowKey, "input_material_name") ? null : r.inputMaterialName;
            String     effOmt           = data.shouldSkipField("mat_bom", bomRowKey, "output_material_type") ? null : r.outputMaterialType;
            BigDecimal effDefectRate    = data.shouldSkipField("mat_bom", bomRowKey, "defect_rate")     ? null : r.defectRate;
            BigDecimal effCompositionPct = data.shouldSkipField("mat_bom", bomRowKey, "composition_pct") ? null : r.compositionPct;
            int updated = em.createNativeQuery(
                    "UPDATE mat_bom SET " +
                    "  input_material_name = COALESCE(:imn, input_material_name), " +
                    "  loss_rate = COALESCE(:lr, loss_rate), " +
                    "  gross_qty = COALESCE(:gq, gross_qty), " +
                    "  net_qty = COALESCE(:nq, net_qty), " +
                    "  gross_unit = COALESCE(:gu, gross_unit), " +
                    "  net_unit = COALESCE(:nu, net_unit), " +
                    "  output_material_type = COALESCE(:omt, output_material_type), " +
                    "  defect_rate = COALESCE(:dr, defect_rate), " +
                    "  composition_pct = COALESCE(:cp, composition_pct), " +
                    "  updated_at = :now, updated_by = :uid " +
                    "WHERE bom_type = :bt AND hf_part_no = :pn AND seq_no = :sn " +
                    "  AND COALESCE(input_material_no,'') = COALESCE(:imno,'') " +
                    "  AND COALESCE(element_name,'') = COALESCE(:en,'')")
                    .setParameter("imn", effImName)
                    .setParameter("lr", effLossRate)
                    .setParameter("gq", effGrossQty)
                    .setParameter("nq", effNetQty)
                    .setParameter("gu", effGrossUnit)
                    .setParameter("nu", effNetUnit)
                    .setParameter("omt", effOmt)
                    .setParameter("dr", effDefectRate)
                    .setParameter("cp", effCompositionPct)
                    .setParameter("now", now)
                    .setParameter("uid", userId)
                    .setParameter("bt", r.bomType)
                    .setParameter("pn", r.hfPartNo)
                    .setParameter("sn", r.seqNo)
                    .setParameter("imno", r.inputMaterialNo)
                    .setParameter("en", r.elementName)
                    .executeUpdate();
            if (updated == 0) {
                em.createNativeQuery(
                        "INSERT INTO mat_bom(id, bom_type, hf_part_no, seq_no, input_material_no, " +
                        "  input_material_name, loss_rate, gross_qty, net_qty, gross_unit, net_unit, " +
                        "  output_material_type, defect_rate, element_name, composition_pct, " +
                        "  created_at, updated_at, created_by, updated_by) " +
                        "VALUES (gen_random_uuid(), :bt, :pn, :sn, :imno, :imn, :lr, :gq, :nq, " +
                        "  :gu, :nu, :omt, :dr, :en, :cp, :now, :now, :uid, :uid)")
                        .setParameter("bt", r.bomType)
                        .setParameter("pn", r.hfPartNo)
                        .setParameter("sn", r.seqNo)
                        .setParameter("imno", r.inputMaterialNo)
                        .setParameter("imn", effImName != null ? effImName : r.inputMaterialName)
                        .setParameter("lr", effLossRate != null ? effLossRate : r.lossRate)
                        .setParameter("gq", effGrossQty != null ? effGrossQty : r.grossQty)
                        .setParameter("nq", effNetQty != null ? effNetQty : r.netQty)
                        .setParameter("gu", effGrossUnit != null ? effGrossUnit : r.grossUnit)
                        .setParameter("nu", effNetUnit != null ? effNetUnit : r.netUnit)
                        .setParameter("omt", effOmt != null ? effOmt : r.outputMaterialType)
                        .setParameter("dr", effDefectRate != null ? effDefectRate : r.defectRate)
                        .setParameter("en", r.elementName)
                        .setParameter("cp", effCompositionPct != null ? effCompositionPct : r.compositionPct)
                        .setParameter("now", now)
                        .setParameter("uid", userId)
                        .executeUpdate();
                stats.matBomCreated++;
            } else {
                stats.matBomUpdated++;
            }
        }

        // 3. plating_plan / mat_plating_plan (全局表, UPSERT by plan_code + version + seq_no)
        // V125: PlatingPlanRow.targetTable 区分目标表 (plating_plan 旧 / mat_plating_plan 报价侧).
        for (ParsedBasicData.PlatingPlanRow r : data.platingPlans) {
            String ppTable = (r.targetTable != null) ? r.targetTable : "plating_plan";
            String ppRowKey = r.planCode + ":" + r.version + ":" + r.seqNo;
            if (data.shouldSkipRow(ppTable, ppRowKey)) continue;
            String     effPlatingElement    = data.shouldSkipField(ppTable, ppRowKey, "plating_element")     ? null : r.platingElement;
            BigDecimal effPlatingArea       = data.shouldSkipField(ppTable, ppRowKey, "plating_area")        ? null : r.platingArea;
            BigDecimal effCoatingThickness  = data.shouldSkipField(ppTable, ppRowKey, "coating_thickness")   ? null : r.coatingThickness;
            String     effPlatingReq        = data.shouldSkipField(ppTable, ppRowKey, "plating_requirement") ? null : r.platingRequirement;
            int updated = em.createNativeQuery(
                    "UPDATE " + ppTable + " SET " +
                    "  plating_element = COALESCE(:pe, plating_element), " +
                    "  plating_area = COALESCE(:pa, plating_area), " +
                    "  coating_thickness = COALESCE(:ct, coating_thickness), " +
                    "  plating_requirement = COALESCE(:pr, plating_requirement), " +
                    "  updated_at = :now, updated_by = :uid " +
                    "WHERE plan_code = :pc AND version = :v AND seq_no = :sn")
                    .setParameter("pe", effPlatingElement)
                    .setParameter("pa", effPlatingArea)
                    .setParameter("ct", effCoatingThickness)
                    .setParameter("pr", effPlatingReq)
                    .setParameter("now", now)
                    .setParameter("uid", userId)
                    .setParameter("pc", r.planCode)
                    .setParameter("v", r.version)
                    .setParameter("sn", r.seqNo)
                    .executeUpdate();
            if (updated == 0) {
                em.createNativeQuery(
                        "INSERT INTO " + ppTable + "(id, plan_code, version, seq_no, plating_element, " +
                        "  plating_area, coating_thickness, plating_requirement, " +
                        "  created_at, updated_at, created_by, updated_by) " +
                        "VALUES (gen_random_uuid(), :pc, :v, :sn, :pe, :pa, :ct, :pr, :now, :now, :uid, :uid)")
                        .setParameter("pc", r.planCode)
                        .setParameter("v", r.version)
                        .setParameter("sn", r.seqNo)
                        .setParameter("pe", effPlatingElement != null ? effPlatingElement : r.platingElement)
                        .setParameter("pa", effPlatingArea != null ? effPlatingArea : r.platingArea)
                        .setParameter("ct", effCoatingThickness != null ? effCoatingThickness : r.coatingThickness)
                        .setParameter("pr", effPlatingReq != null ? effPlatingReq : r.platingRequirement)
                        .setParameter("now", now)
                        .setParameter("uid", userId)
                        .executeUpdate();
                stats.platingPlanCreated++;
            }
        }

        // 4. mat_customer_part_mapping（UPSERT by customer_id + customer_product_no）
        for (ParsedBasicData.MappingRow r : data.mappings) {
            // rowKey = customer_id:customer_product_no（与 BV-06 及 validateOldValuesOrThrow409 一致）
            String mapRowKey = r.customerId + ":" + r.customerProductNo;
            if (data.shouldSkipRow("mat_customer_part_mapping", mapRowKey)) continue;
            String effCpName    = data.shouldSkipField("mat_customer_part_mapping", mapRowKey, "customer_part_name")  ? null : r.customerPartName;
            String effCdNo      = data.shouldSkipField("mat_customer_part_mapping", mapRowKey, "customer_drawing_no") ? null : r.customerDrawingNo;
            String effPm        = data.shouldSkipField("mat_customer_part_mapping", mapRowKey, "payment_method")      ? null : r.paymentMethod;
            String effBc        = data.shouldSkipField("mat_customer_part_mapping", mapRowKey, "base_currency")       ? null : r.baseCurrency;
            String effQc        = data.shouldSkipField("mat_customer_part_mapping", mapRowKey, "quote_currency")      ? null : r.quoteCurrency;
            int updated = em.createNativeQuery(
                    "UPDATE mat_customer_part_mapping SET " +
                    "  customer_part_name = COALESCE(:cpn, customer_part_name), " +
                    "  customer_drawing_no = COALESCE(:cdn, customer_drawing_no), " +
                    "  hf_part_no = :hfpn, " +
                    "  payment_method = COALESCE(:pm, payment_method), " +
                    "  base_currency = COALESCE(:bc, base_currency), " +
                    "  quote_currency = COALESCE(:qc, quote_currency), " +
                    "  updated_at = :now, updated_by = :uid " +
                    "WHERE customer_id = :cid AND customer_product_no = :cpno")
                    .setParameter("cpn", effCpName)
                    .setParameter("cdn", effCdNo)
                    .setParameter("hfpn", r.hfPartNo)
                    .setParameter("pm", effPm)
                    .setParameter("bc", effBc)
                    .setParameter("qc", effQc)
                    .setParameter("now", now)
                    .setParameter("uid", userId)
                    .setParameter("cid", r.customerId)
                    .setParameter("cpno", r.customerProductNo)
                    .executeUpdate();
            if (updated == 0) {
                em.createNativeQuery(
                        "INSERT INTO mat_customer_part_mapping(id, customer_id, customer_part_name, " +
                        "  customer_product_no, customer_drawing_no, hf_part_no, payment_method, " +
                        "  base_currency, quote_currency, created_at, updated_at, created_by, updated_by) " +
                        "VALUES (gen_random_uuid(), :cid, :cpn, :cpno, :cdn, :hfpn, :pm, :bc, :qc, :now, :now, :uid, :uid)")
                        .setParameter("cid", r.customerId)
                        .setParameter("cpn", effCpName != null ? effCpName : r.customerPartName)
                        .setParameter("cpno", r.customerProductNo)
                        .setParameter("cdn", effCdNo != null ? effCdNo : r.customerDrawingNo)
                        .setParameter("hfpn", r.hfPartNo)
                        .setParameter("pm", effPm != null ? effPm : r.paymentMethod)
                        .setParameter("bc", effBc != null ? effBc : r.baseCurrency)
                        .setParameter("qc", effQc != null ? effQc : r.quoteCurrency)
                        .setParameter("now", now)
                        .setParameter("uid", userId)
                        .executeUpdate();
                stats.mappingCreated++;
            } else {
                stats.mappingUpdated++;
            }
        }

        // 4.5 客户料号 → product 行同步（按 PRD 设计）：
        //     键: product.part_no = mapping.customer_product_no（每个客户的产品号一行）
        //     name = mapping.customer_part_name；分类固定为「默认分类」
        //     drawing_no 也带过来；ON CONFLICT (part_no) DO NOTHING — 已存在不动
        //     注：与 step 1.5（mat_part → STANDARD 分类同步）的语义不同 ——
        //     1.5 同步的是"生产料号"（已被移除）；4.5 同步的是"客户料号"，是用户在产品管理列表里希望看到的。
        @SuppressWarnings("unchecked")
        List<Object> defaultCategoryRows = em.createNativeQuery(
                "SELECT id FROM product_category WHERE name = '默认分类' LIMIT 1")
                .getResultList();
        UUID defaultCategoryId = defaultCategoryRows.isEmpty() ? null : (UUID) defaultCategoryRows.get(0);
        for (ParsedBasicData.MappingRow r : data.mappings) {
            if (r.customerProductNo == null || r.customerProductNo.isBlank()) continue;
            String displayName = (r.customerPartName != null && !r.customerPartName.isBlank())
                    ? r.customerPartName
                    : r.customerProductNo;
            em.createNativeQuery(
                    "INSERT INTO product(id, name, part_no, category, category_id, drawing_no, " +
                    "  status, tags, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), :name, :pn, '默认分类', :catId, :drw, " +
                    "  'ACTIVE', '[]'::jsonb, :now, :now) " +
                    "ON CONFLICT (part_no) DO NOTHING")
                    .setParameter("name", displayName)
                    .setParameter("pn", r.customerProductNo)
                    .setParameter("catId", defaultCategoryId)
                    .setParameter("drw", r.customerDrawingNo)
                    .setParameter("now", now)
                    .executeUpdate();
        }

        // 5. mat_process（VersionedWriter 版本化写入）
        for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
            // rowKey = customer_id:hf_part_no:seq_no（与 detectCustomerDataConflicts 一致）
            String procRowKey = r.customerId + ":" + r.hfPartNo + ":" + r.seqNo;
            // KEEP_OLD on any field → skip entire row (versioned table, don't create new version)
            if (data.shouldSkipRow("mat_process", procRowKey)) continue;
            Set<String> keptFields = data.skipFields.get("mat_process:" + procRowKey);
            if (keptFields != null && !keptFields.isEmpty()) continue;

            Map<String, Object> bk = new LinkedHashMap<>();
            bk.put("seq_no", r.seqNo);
            bk.put("sub_seq_no", r.subSeqNo);

            Map<String, Object> fv = new LinkedHashMap<>();
            fv.put("process_code", r.processCode);
            fv.put("assembly_process", r.assemblyProcess);
            fv.put("component_part_no", r.componentPartNo);
            fv.put("component_name", r.componentName);
            fv.put("supplier_code", r.supplierCode);
            fv.put("supplier_name", r.supplierName);
            fv.put("quantity", r.quantity);
            fv.put("quantity_unit", r.quantityUnit);
            fv.put("unit_price", r.unitPrice);
            fv.put("freight", r.freight);
            fv.put("currency", r.currency);
            fv.put("price_unit", r.priceUnit);
            fv.put("status", "ACTIVE");

            String procNote = firstNonNullNote(resolutions, "mat_process", procRowKey);
            VersionedWriter.WriteResult wr = versionedWriter.writeWithVersioning(
                    new VersionedWriter.WriteRequest(
                            "mat_process", r.customerId, r.hfPartNo, bk, fv,
                            userId, importRecordId, "V5_IMPORT", procNote));

            if (wr.isFirstInsert()) stats.matProcessCreated++;
            else if (!wr.noChange()) stats.matProcessVersioned++;
            stats.changeLogRows += wr.changeLogEntriesWritten();
        }

        // 6. mat_fee（VersionedWriter 版本化写入）
        for (ParsedBasicData.MatFeeRow r : data.matFees) {
            // V70: rowKey 同步扩到 dim_* 维度，与 detectCustomerDataConflicts 一致，
            // 让 KEEP_OLD 按完整键找到并跳过；否则同 (fee_type, seq_no) 多行只会按粗粒度跳。
            String feeRowKey = r.customerId + ":" + r.hfPartNo + ":" + r.feeType + ":" + r.seqNo
                    + ":" + nullToEmpty(r.dimInputMaterialNo)
                    + ":" + nullToEmpty(r.dimInputMaterialName)
                    + ":" + nullToEmpty(r.dimElementName)
                    + ":" + nullToEmpty(r.dimAssemblyProcess)
                    + ":" + (r.dimSubSeqNo == null ? "" : r.dimSubSeqNo);
            // KEEP_OLD on any field → skip entire row (versioned table, don't create new version)
            if (data.shouldSkipRow("mat_fee", feeRowKey)) continue;
            Set<String> keptFeeFields = data.skipFields.get("mat_fee:" + feeRowKey);
            if (keptFeeFields != null && !keptFeeFields.isEmpty()) continue;

            // V70: 业务键继续扩到 5 个 dim_* 维度，对齐 schema uq_mat_fee_current。
            // 同 (fee_type, seq_no) 下不同 dim_element_name / dim_input_material_no 行（典型：
            // 来料 H85 + 包装费 / 材料管理费 / 回收费 三行同 seq_no=2，需独立 current）。
            Map<String, Object> bk = new LinkedHashMap<>();
            bk.put("fee_type", r.feeType);
            bk.put("seq_no", r.seqNo);
            bk.put("dim_input_material_no", r.dimInputMaterialNo);
            bk.put("dim_input_material_name", r.dimInputMaterialName);
            bk.put("dim_element_name", r.dimElementName);
            bk.put("dim_assembly_process", r.dimAssemblyProcess);
            bk.put("dim_sub_seq_no", r.dimSubSeqNo);

            // 业务键已经包含 dim_*，不要重复放进 dataColumns 否则 VersionedWriter 拒绝
            Map<String, Object> fv = new LinkedHashMap<>();
            fv.put("fee_value", r.feeValue);
            fv.put("fee_ratio", r.feeRatio);
            fv.put("currency", r.currency);
            fv.put("price_unit", r.priceUnit);
            fv.put("price_floating", r.priceFloating);
            fv.put("settlement_rise_ratio", r.settlementRiseRatio);
            fv.put("fixed_rise_value", r.fixedRiseValue);
            fv.put("rise_currency", r.riseCurrency);
            fv.put("rise_unit", r.riseUnit);
            fv.put("reject_rate", r.rejectRate);
            fv.put("status", "ACTIVE");

            String feeNote = firstNonNullNote(resolutions, "mat_fee", feeRowKey);
            VersionedWriter.WriteResult wr = versionedWriter.writeWithVersioning(
                    new VersionedWriter.WriteRequest(
                            "mat_fee", r.customerId, r.hfPartNo, bk, fv,
                            userId, importRecordId, "V5_IMPORT", feeNote));

            if (wr.isFirstInsert()) stats.matFeeCreated++;
            else if (!wr.noChange()) stats.matFeeVersioned++;
            stats.changeLogRows += wr.changeLogEntriesWritten();
        }

        // 7. plating_fee / mat_plating_fee (VersionedWriter 版本化写入,
        //    by customer_id + hf_part_no + plating_plan_code + plan_version)
        // V125: PlatingFeeRow.targetTable 区分写入目标表 (plating_fee 旧 / mat_plating_fee 报价侧).
        for (ParsedBasicData.PlatingFeeRow r : data.platingFees) {
            String pfTable = (r.targetTable != null) ? r.targetTable : "plating_fee";
            // rowKey = customer_id:hf_part_no:plating_plan_code:plan_version (与 validateOldValuesOrThrow409 一致)
            String pfRowKey = r.customerId + ":" + r.hfPartNo + ":" + r.platingPlanCode + ":" + r.planVersion;
            // KEEP_OLD on any field → skip entire row (versioned table, don't create new version)
            if (data.shouldSkipRow(pfTable, pfRowKey)) continue;
            Set<String> keptPfFields = data.skipFields.get(pfTable + ":" + pfRowKey);
            if (keptPfFields != null && !keptPfFields.isEmpty()) continue;

            Map<String, Object> bk = new LinkedHashMap<>();
            bk.put("plating_plan_code", r.platingPlanCode);
            bk.put("plan_version", r.planVersion);

            Map<String, Object> fv = new LinkedHashMap<>();
            fv.put("plating_process_fee", r.platingProcessFee);
            fv.put("plating_material_fee", r.platingMaterialFee);
            fv.put("currency", r.currency);
            fv.put("price_unit", r.priceUnit);
            fv.put("defect_rate", r.defectRate);
            fv.put("status", "ACTIVE");

            String pfNote = firstNonNullNote(resolutions, pfTable, pfRowKey);
            VersionedWriter.WriteResult wr = versionedWriter.writeWithVersioning(
                    new VersionedWriter.WriteRequest(
                            pfTable, r.customerId, r.hfPartNo, bk, fv,
                            userId, importRecordId, "V5_IMPORT", pfNote));

            if (wr.isFirstInsert()) stats.platingFeeCreated++;
            else if (!wr.noChange()) stats.platingFeeVersioned++;
            stats.changeLogRows += wr.changeLogEntriesWritten();
        }

        // 8. V90: 核价料号级 8 张表（costing_part_*），按 targetTable 分发 UPSERT
        writeCostingPartRows(data, stats);

        return stats;
    }

    // ── V90: 核价料号级 8 张表 UPSERT 实现 ─────────────────────────────────

    /**
     * V90: 把 ParsedBasicData.costingPartRows 按 targetTable 分发到 8 个 UPSERT 助手。
     * 任何一行写入失败仅记 LOG, 不抛异常 → 不阻塞其它行/其它表。
     */
    private void writeCostingPartRows(ParsedBasicData data, ImportResultDTO stats) {
        if (data.costingPartRows == null || data.costingPartRows.isEmpty()) return;
        for (ParsedBasicData.CostingPartRow r : data.costingPartRows) {
            try {
                switch (r.targetTable) {
                    case "costing_part_process_cost"  -> upsertCostingProcessCost(r, stats);
                    case "costing_part_tooling_cost"  -> upsertCostingToolingCost(r, stats);
                    case "costing_part_material_bom"  -> upsertCostingMaterialBom(r, stats);
                    case "costing_part_element_bom"   -> upsertCostingElementBom(r, stats);
                    case "costing_part_quality_check" -> upsertCostingQualityCheck(r, stats);
                    case "costing_part_plating"       -> upsertCostingPartPlating(r, stats);
                    case "costing_part_plating_fee"   -> upsertCostingPlatingFee(r, stats);
                    case "costing_part_design_cost"   -> upsertCostingDesignCost(r, stats);
                    case "costing_part_weight"        -> upsertCostingWeight(r, stats);
                    default -> LOG.warnf("V90: unknown costing_part target '%s' row %d", r.targetTable, r.rowNum);
                }
            } catch (Exception e) {
                LOG.errorf(e, "V90: 写入 %s 第 %d 行失败: %s", r.targetTable, r.rowNum, e.getMessage());
            }
        }
        LOG.infof("V90: 核价料号级数据写入完成, 总行数=%d, 成功=%d",
                data.costingPartRows.size(), stats.costingPartRowsWritten);
    }

    /** 解析 是否有效/是否生效 等 BOOLEAN 字段, 接受 中文是/否 + true/false/Y/N/1/0, null/空 默认 true */
    private static boolean toActiveFlag(String v) {
        if (v == null || v.isBlank()) return true;
        String s = v.trim();
        if ("否".equals(s) || "false".equalsIgnoreCase(s) || "N".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return true;
    }

    private void upsertCostingProcessCost(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String costType = r.discriminator.getOrDefault("cost_type", r.values.get("cost_type"));
        if (costType == null || costType.isBlank()) {
            LOG.warnf("V90: costing_part_process_cost 行 %d 缺少 cost_type, 跳过", r.rowNum);
            return;
        }
        String sql = "INSERT INTO costing_part_process_cost(" +
                "  hf_part_no, process_no, process_name, cost_type, unit_price, " +
                "  currency, unit, ref_calc_version, is_active, created_at, updated_at" +
                ") VALUES (:hf, :pn, :pname, :ct, :price, " +
                "  COALESCE(:cur, 'CNY'), COALESCE(:unit, 'KG'), :ver, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, process_no, cost_type, part_version) DO UPDATE SET " +
                "  process_name = COALESCE(EXCLUDED.process_name, costing_part_process_cost.process_name), " +
                "  unit_price = EXCLUDED.unit_price, " +
                "  currency = EXCLUDED.currency, unit = EXCLUDED.unit, " +
                "  ref_calc_version = EXCLUDED.ref_calc_version, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf",     r.values.get("hf_part_no"))
                .setParameter("pn",     r.values.get("process_no"))
                .setParameter("pname",  r.values.get("process_name"))
                .setParameter("ct",     costType)
                .setParameter("price",  toDecimal(r.values.get("unit_price")))
                .setParameter("cur",    r.values.get("currency"))
                .setParameter("unit",   r.values.get("unit"))
                .setParameter("ver",    r.values.get("ref_calc_version"))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingToolingCost(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        Integer seqNo = toInt(r.values.get("seq_no"));
        if (seqNo == null) seqNo = 1;
        String sql = "INSERT INTO costing_part_tooling_cost(" +
                "  hf_part_no, process_no, process_name, seq_no, tooling_no, " +
                "  tooling_unit_cost, process_count, cycle_count, unit_price, " +
                "  currency, unit, is_active, created_at, updated_at" +
                ") VALUES (:hf, :pn, :pname, :seq, :tno, :cost, :pcnt, :ccnt, :uprice, " +
                "  COALESCE(:cur, 'CNY'), COALESCE(:unit, 'PCS'), :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, process_no, seq_no, part_version) DO UPDATE SET " +
                "  process_name = COALESCE(EXCLUDED.process_name, costing_part_tooling_cost.process_name), " +
                "  tooling_no = EXCLUDED.tooling_no, " +
                "  tooling_unit_cost = EXCLUDED.tooling_unit_cost, " +
                "  process_count = EXCLUDED.process_count, " +
                "  cycle_count = EXCLUDED.cycle_count, " +
                "  unit_price = EXCLUDED.unit_price, " +
                "  currency = EXCLUDED.currency, unit = EXCLUDED.unit, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf",     r.values.get("hf_part_no"))
                .setParameter("pn",     r.values.get("process_no"))
                .setParameter("pname",  r.values.get("process_name"))
                .setParameter("seq",    seqNo)
                .setParameter("tno",    r.values.get("tooling_no"))
                .setParameter("cost",   toDecimal(r.values.get("tooling_unit_cost")))
                .setParameter("pcnt",   toInt(r.values.get("process_count")))
                .setParameter("ccnt",   toInt(r.values.get("cycle_count")))
                .setParameter("uprice", toDecimal(r.values.get("unit_price")))
                .setParameter("cur",    r.values.get("currency"))
                .setParameter("unit",   r.values.get("unit"))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingMaterialBom(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        Integer seqNo = toInt(r.values.get("seq_no"));
        if (seqNo == null) seqNo = r.rowNum;  // 兜底用行号
        String sql = "INSERT INTO costing_part_material_bom(" +
                "  hf_part_no, seq_no, input_material_no, process_no, process_name, " +
                "  input_qty, input_unit, output_qty, output_unit, output_loss_rate, " +
                "  fixed_loss_qty, loss_rate, is_active, created_at, updated_at" +
                ") VALUES (:hf, :seq, :imn, :pn, :pname, :iqty, :iunit, :oqty, :ounit, " +
                "  :olr, :flq, :lr, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, seq_no, part_version) DO UPDATE SET " +
                "  input_material_no = EXCLUDED.input_material_no, " +
                "  process_no = EXCLUDED.process_no, process_name = EXCLUDED.process_name, " +
                "  input_qty = EXCLUDED.input_qty, input_unit = EXCLUDED.input_unit, " +
                "  output_qty = EXCLUDED.output_qty, output_unit = EXCLUDED.output_unit, " +
                "  output_loss_rate = EXCLUDED.output_loss_rate, " +
                "  fixed_loss_qty = EXCLUDED.fixed_loss_qty, " +
                "  loss_rate = EXCLUDED.loss_rate, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf",    r.values.get("hf_part_no"))
                .setParameter("seq",   seqNo)
                .setParameter("imn",   r.values.get("input_material_no"))
                .setParameter("pn",    r.values.get("process_no"))
                .setParameter("pname", r.values.get("process_name"))
                .setParameter("iqty",  toDecimal(r.values.get("input_qty")))
                .setParameter("iunit", r.values.get("input_unit"))
                .setParameter("oqty",  toDecimal(r.values.get("output_qty")))
                .setParameter("ounit", r.values.get("output_unit"))
                .setParameter("olr",   toDecimal(r.values.get("output_loss_rate")))
                .setParameter("flq",   toDecimal(r.values.get("fixed_loss_qty")))
                .setParameter("lr",    toDecimal(r.values.get("loss_rate")))
                .setParameter("active",toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingElementBom(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String elementCode = r.values.get("element_code");
        if (elementCode == null || elementCode.isBlank()) {
            LOG.warnf("V90: costing_part_element_bom 行 %d 缺少 element_code, 跳过", r.rowNum);
            return;
        }
        Integer seqNo = toInt(r.values.get("seq_no"));
        if (seqNo == null) seqNo = 1;
        String sql = "INSERT INTO costing_part_element_bom(" +
                "  input_material_no, seq_no, element_code, composition_pct, loss_rate, " +
                "  is_active, created_at, updated_at" +
                ") VALUES (:imn, :seq, :ec, :cp, :lr, :active, now(), now()) " +
                "ON CONFLICT (input_material_no, seq_no, element_code, part_version) DO UPDATE SET " +
                "  composition_pct = EXCLUDED.composition_pct, " +
                "  loss_rate = EXCLUDED.loss_rate, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("imn", r.values.get("input_material_no"))
                .setParameter("seq", seqNo)
                .setParameter("ec",  elementCode)
                .setParameter("cp",  toDecimal(r.values.get("composition_pct")))
                .setParameter("lr",  toDecimal(r.values.get("loss_rate")))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingQualityCheck(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String stage = r.discriminator.getOrDefault("stage", r.values.get("stage"));
        if (stage == null || stage.isBlank()) stage = "INCOMING";
        Integer primarySeq = toInt(r.values.get("primary_seq_no"));
        Integer seqNo = toInt(r.values.get("seq_no"));
        if (seqNo == null) seqNo = 1;
        String sql = "INSERT INTO costing_part_quality_check(" +
                "  hf_part_no, stage, primary_seq_no, seq_no, requirement_code, requirement_desc, " +
                "  scrap_rate, is_active, created_at, updated_at" +
                ") VALUES (:hf, :stage, :pseq, :seq, :rcode, :rdesc, :sr, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, stage, primary_seq_no, seq_no, part_version) DO UPDATE SET " +
                "  requirement_code = EXCLUDED.requirement_code, " +
                "  requirement_desc = EXCLUDED.requirement_desc, " +
                "  scrap_rate = EXCLUDED.scrap_rate, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf",    r.values.get("hf_part_no"))
                .setParameter("stage", stage)
                .setParameter("pseq",  primarySeq)
                .setParameter("seq",   seqNo)
                .setParameter("rcode", r.values.get("requirement_code"))
                .setParameter("rdesc", r.values.get("requirement_desc"))
                .setParameter("sr",    toDecimal(r.values.get("scrap_rate")))
                .setParameter("active",toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingPartPlating(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String platingNo = r.values.get("plating_no");
        String version  = r.values.get("version_number");
        if (platingNo == null || version == null) {
            LOG.warnf("V90: costing_part_plating 行 %d 缺 plating_no 或 version_number, 跳过", r.rowNum);
            return;
        }
        Integer seqNo = toInt(r.values.get("seq_no"));
        if (seqNo == null) seqNo = 1;
        String sql = "INSERT INTO costing_part_plating(" +
                "  plating_no, version_number, seq_no, element_attr, " +
                "  plating_area_cm2, layer_thickness_um, requirement, is_active, " +
                "  created_at, updated_at" +
                ") VALUES (:pno, :ver, :seq, :ea, :area, :th, :req, :active, now(), now()) " +
                "ON CONFLICT (plating_no, version_number, seq_no, part_version) DO UPDATE SET " +
                "  element_attr = EXCLUDED.element_attr, " +
                "  plating_area_cm2 = EXCLUDED.plating_area_cm2, " +
                "  layer_thickness_um = EXCLUDED.layer_thickness_um, " +
                "  requirement = EXCLUDED.requirement, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("pno", platingNo)
                .setParameter("ver", version)
                .setParameter("seq", seqNo)
                .setParameter("ea",  r.values.get("element_attr"))
                .setParameter("area", toDecimal(r.values.get("plating_area_cm2")))
                .setParameter("th",   toDecimal(r.values.get("layer_thickness_um")))
                .setParameter("req",  r.values.get("requirement"))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    /**
     * V125: 核价侧电镀费用 UPSERT — UNIQUE (hf_part_no, plating_plan_code, plan_version).
     * 业务键 plan_code/plan_version 缺失时用 "" 占位以保 ON CONFLICT 语义.
     */
    private void upsertCostingPlatingFee(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String hfPartNo = r.values.get("hf_part_no");
        if (hfPartNo == null || hfPartNo.isBlank()) {
            LOG.warnf("V125: costing_part_plating_fee 行 %d 缺少 hf_part_no, 跳过", r.rowNum);
            return;
        }
        String planCode = r.values.get("plating_plan_code");
        String planVer  = r.values.get("plan_version");
        if (planCode == null) planCode = "";
        if (planVer  == null) planVer  = "";
        String sql = "INSERT INTO costing_part_plating_fee(" +
                "  hf_part_no, plating_plan_code, plan_version, plating_process_fee, " +
                "  plating_material_fee, currency, price_unit, defect_rate, is_active, " +
                "  created_at, updated_at" +
                ") VALUES (:hf, :pc, :pv, :pf, :mf, :cur, :pu, :dr, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, plating_plan_code, plan_version, part_version) DO UPDATE SET " +
                "  plating_process_fee = EXCLUDED.plating_process_fee, " +
                "  plating_material_fee = EXCLUDED.plating_material_fee, " +
                "  currency = EXCLUDED.currency, price_unit = EXCLUDED.price_unit, " +
                "  defect_rate = EXCLUDED.defect_rate, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf", hfPartNo)
                .setParameter("pc", planCode)
                .setParameter("pv", planVer)
                .setParameter("pf", toDecimal(r.values.get("plating_process_fee")))
                .setParameter("mf", toDecimal(r.values.get("plating_material_fee")))
                .setParameter("cur", r.values.get("currency"))
                .setParameter("pu",  r.values.get("price_unit"))
                .setParameter("dr",  toDecimalPercent(r.values.get("defect_rate")))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingDesignCost(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        String drawingNo = r.values.get("design_drawing_no");
        String version   = r.values.get("version_number");
        // 这两个字段是 unique key 的一部分; 缺失时用空字符串占位以保证 ON CONFLICT 语义
        if (drawingNo == null) drawingNo = "";
        if (version == null) version = "";
        String sql = "INSERT INTO costing_part_design_cost(" +
                "  hf_part_no, design_drawing_no, version_number, design_proc_fee, " +
                "  design_material_fee, currency, unit, loss_rate, is_active, " +
                "  created_at, updated_at" +
                ") VALUES (:hf, :dno, :ver, :dpf, :dmf, COALESCE(:cur,'CNY'), COALESCE(:unit,'KG'), " +
                "  :lr, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, design_drawing_no, version_number, part_version) DO UPDATE SET " +
                "  design_proc_fee = EXCLUDED.design_proc_fee, " +
                "  design_material_fee = EXCLUDED.design_material_fee, " +
                "  currency = EXCLUDED.currency, unit = EXCLUDED.unit, " +
                "  loss_rate = EXCLUDED.loss_rate, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf",  r.values.get("hf_part_no"))
                .setParameter("dno", drawingNo)
                .setParameter("ver", version)
                .setParameter("dpf", toDecimal(r.values.get("design_proc_fee")))
                .setParameter("dmf", toDecimal(r.values.get("design_material_fee")))
                .setParameter("cur", r.values.get("currency"))
                .setParameter("unit",r.values.get("unit"))
                .setParameter("lr",  toDecimal(r.values.get("loss_rate")))
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }

    private void upsertCostingWeight(ParsedBasicData.CostingPartRow r, ImportResultDTO stats) {
        java.math.BigDecimal w = toDecimal(r.values.get("weight_g_per_pcs"));
        if (w == null) {
            LOG.warnf("V90: costing_part_weight 行 %d 缺少 weight_g_per_pcs, 跳过", r.rowNum);
            return;
        }
        String sql = "INSERT INTO costing_part_weight(" +
                "  hf_part_no, weight_g_per_pcs, is_active, created_at, updated_at" +
                ") VALUES (:hf, :w, :active, now(), now()) " +
                "ON CONFLICT (hf_part_no, part_version) DO UPDATE SET " +
                "  weight_g_per_pcs = EXCLUDED.weight_g_per_pcs, " +
                "  is_active = EXCLUDED.is_active, updated_at = now()";
        em.createNativeQuery(sql)
                .setParameter("hf", r.values.get("hf_part_no"))
                .setParameter("w",  w)
                .setParameter("active", toActiveFlag(r.values.get("is_active")))
                .executeUpdate();
        stats.costingPartRowsWritten++;
    }


    // ── 审计变更日志（REQUIRES_NEW 独立事务）────────────────────────────

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeChangeLogs(ParsedBasicData data, UUID customerId,
                                 UUID userId, UUID importRecordId, ImportResultDTO stats) {
        OffsetDateTime now = OffsetDateTime.now();

        // mat_part 变更
        for (ParsedBasicData.MatPartRow r : data.matParts) {
            writeChangeLog("mat_part", r.partNo, null, null,
                    stats.matPartCreated > 0 ? "CREATE" : "UPDATE",
                    importRecordId, userId, now);
        }
        // mat_bom 变更
        for (ParsedBasicData.MatBomRow r : data.matBoms) {
            writeChangeLog("mat_bom", r.hfPartNo, null, null,
                    stats.matBomCreated > 0 ? "CREATE" : "UPDATE",
                    importRecordId, userId, now);
        }
        // mat_process 变更
        for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
            writeChangeLog("mat_process", r.hfPartNo, customerId,
                    stats.matProcessVersioned > 0 ? "新版本" : "新建",
                    stats.matProcessVersioned > 0 ? "NEW_VERSION" : "CREATE",
                    importRecordId, userId, now);
        }

        stats.changeLogRows = data.matParts.size() + data.matBoms.size() + data.matProcesses.size();
        LOG.debugf("Wrote %d change log entries for import record %s", stats.changeLogRows, importRecordId);
    }

    private void writeChangeLog(String tableName, String recordKey,
                                 UUID customerId, String remarks,
                                 String changeType, UUID importRecordId,
                                 UUID userId, OffsetDateTime now) {
        // business_key as JSONB
        String businessKey = customerId != null
                ? "{\"part_no\":\"" + escapeJson(recordKey) + "\",\"customer_id\":\"" + customerId + "\"}"
                : "{\"part_no\":\"" + escapeJson(recordKey) + "\"}";

        try {
            em.createNativeQuery(
                    "INSERT INTO basic_data_change_log(id, table_name, record_id, business_key, " +
                    "  change_type, import_record_id, changed_by, changed_at, remarks, " +
                    "  created_at, updated_at, created_by, updated_by) " +
                    "VALUES (gen_random_uuid(), :tn, gen_random_uuid(), CAST(:bk AS jsonb), " +
                    "  :ct, :rid, :uid, :now, :rm, :now, :now, :uid, :uid)")
                    .setParameter("tn", tableName)
                    .setParameter("bk", businessKey)
                    .setParameter("ct", changeType)
                    .setParameter("rid", importRecordId)
                    .setParameter("uid", userId)
                    .setParameter("now", now)
                    .setParameter("rm", remarks)
                    .executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to write change log for %s/%s: %s", tableName, recordKey, e.getMessage());
        }
    }

    // ── ImportRecord 辅助方法 ─────────────────────────────────────────────

    /**
     * 在当前（主）事务中写入 ImportRecord，状态由调用方指定（必须是 SUCCESS/PARTIAL/FAILED）。
     */
    private void writeImportRecord(UUID id, UUID customerId, UUID userId,
                                    String status, int totalRows, int successRows, String errorDetail) {
        writeImportRecord(id, customerId, userId, status, totalRows, successRows, errorDetail, null);
    }

    /**
     * 在当前（主）事务中写入 ImportRecord（含 metadata）。
     */
    private void writeImportRecord(UUID id, UUID customerId, UUID userId,
                                    String status, int totalRows, int successRows,
                                    String errorDetail, String metadata) {
        // Note: Cast ::jsonb cannot be used in the same token as a Hibernate named parameter.
        // Use CAST() function syntax to avoid Hibernate parameter-name parsing issues.
        em.createNativeQuery(
                "INSERT INTO import_record(id, customer_id, imported_by, import_status, " +
                "  original_file_name, original_file_path, total_rows, success_rows, " +
                "  matched_rows, unmatched_rows, mapping_snapshot, error_detail, metadata, created_at) " +
                "VALUES (:id, :cid, :uid, :status, 'v5-import.xlsx', '', :tr, :sr, 0, 0, " +
                "  '{}'::jsonb, CAST(:ed AS jsonb), CAST(:meta AS jsonb), NOW()) " +
                "ON CONFLICT (id) DO UPDATE SET import_status=:status, total_rows=:tr, success_rows=:sr, " +
                "  error_detail=CAST(:ed AS jsonb), metadata=CAST(:meta AS jsonb)")
                .setParameter("id", id)
                .setParameter("cid", customerId)
                .setParameter("uid", userId)
                .setParameter("status", status)
                .setParameter("tr", totalRows)
                .setParameter("sr", successRows)
                .setParameter("ed", errorDetail)
                .setParameter("meta", metadata != null ? metadata : "null")
                .executeUpdate();
    }

    /**
     * 在独立事务（REQUIRES_NEW）中写入 ImportRecord，确保主事务回滚时记录仍被保存。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeImportRecordInNewTx(UUID id, UUID customerId, UUID userId,
                                          String status, int totalRows, int successRows, String errorDetail) {
        writeImportRecord(id, customerId, userId, status, totalRows, successRows, errorDetail);
    }

    // ── 版本查询辅助方法 ─────────────────────────────────────────────────

    private Object[] findCurrentMatProcess(UUID customerId, String hfPartNo, int seqNo, Integer subSeqNo) {
        try {
            Object r = em.createNativeQuery(
                    "SELECT version FROM mat_process " +
                    "WHERE customer_id = :cid AND hf_part_no = :pn AND seq_no = :sn " +
                    "  AND COALESCE(sub_seq_no,-1) = COALESCE(:ssn,-1) AND is_current = true " +
                    "LIMIT 1")
                    .setParameter("cid", customerId)
                    .setParameter("pn", hfPartNo)
                    .setParameter("sn", seqNo)
                    .setParameter("ssn", subSeqNo)
                    .getSingleResult();
            return new Object[]{r};
        } catch (Exception e) {
            return null;
        }
    }

    private Object[] findCurrentMatFee(UUID customerId, String hfPartNo, String feeType, int seqNo) {
        try {
            Object r = em.createNativeQuery(
                    "SELECT version FROM mat_fee " +
                    "WHERE customer_id = :cid AND hf_part_no = :pn AND fee_type = :ft " +
                    "  AND seq_no = :sn AND is_current = true LIMIT 1")
                    .setParameter("cid", customerId)
                    .setParameter("pn", hfPartNo)
                    .setParameter("ft", feeType)
                    .setParameter("sn", seqNo)
                    .getSingleResult();
            return new Object[]{r};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * V125: tableName 显式参数, 路由到 mat_plating_fee (报价侧) 或 plating_fee (旧, 兼容).
     * 报价侧 import 应传 "mat_plating_fee"; 核价侧 import 不走这条 (核价侧用
     * costing_part_plating_fee, 无 customer/version 概念).
     */
    private Object[] findCurrentPlatingFee(String tableName, UUID customerId, String hfPartNo,
                                            String platingPlanCode, String planVersion) {
        if (!"mat_plating_fee".equals(tableName) && !"plating_fee".equals(tableName)) {
            return null;
        }
        try {
            Object r = em.createNativeQuery(
                    "SELECT version FROM " + tableName + " " +
                    "WHERE customer_id = :cid AND hf_part_no = :pn " +
                    "  AND COALESCE(plating_plan_code,'') = COALESCE(:ppc,'') " +
                    "  AND COALESCE(plan_version,'') = COALESCE(:pv,'') " +
                    "  AND is_current = true LIMIT 1")
                    .setParameter("cid", customerId)
                    .setParameter("pn", hfPartNo)
                    .setParameter("ppc", platingPlanCode)
                    .setParameter("pv", planVersion)
                    .getSingleResult();
            return new Object[]{r};
        } catch (Exception e) {
            return null;
        }
    }

    // ── 货币/单位合法性检查辅助 ──────────────────────────────────────────

    /**
     * 货币代码兼容:大小写不敏感,接受中文俗语"元"/"RMB"作为 CNY 别名。
     */
    private static boolean isCurrencyAllowed(String cur, Set<String> allowed) {
        if (cur == null || allowed == null || allowed.isEmpty()) return true;
        String trimmed = cur.trim();
        String upper = trimmed.toUpperCase();
        if (allowed.contains(upper)) return true;
        // 中文俗语 / 别名: 元 ≡ ¥ ≡ RMB ≡ CNY
        if ("元".equals(trimmed) || "¥".equals(trimmed) || "RMB".equals(upper)) {
            return allowed.contains("CNY") || allowed.contains("RMB");
        }
        return false;
    }

    private void checkCurrencyInProcess(List<ParsedBasicData.MatProcessRow> rows,
                                         Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.MatProcessRow r : rows) {
            if (r.currency != null && !isCurrencyAllowed(r.currency, allowed)) {
                vr.addError("BV-15", r.rowNum, sheetNameOf("mat_process", null),
                        "料号 " + r.hfPartNo + " 货币代码 " + r.currency + " 不在允许列表: " + allowed);
            }
        }
    }

    private void checkCurrencyInFees(List<ParsedBasicData.MatFeeRow> rows,
                                      Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.MatFeeRow r : rows) {
            if (r.currency != null && !isCurrencyAllowed(r.currency, allowed)) {
                vr.addError("BV-15", r.rowNum, sheetNameOf("mat_fee", r.feeType),
                        "费用行 " + r.hfPartNo + " 货币代码 " + r.currency + " 不在允许列表: " + allowed);
            }
        }
    }

    private void checkCurrencyInPlatingFees(List<ParsedBasicData.PlatingFeeRow> rows,
                                              Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.PlatingFeeRow r : rows) {
            if (r.currency != null && !isCurrencyAllowed(r.currency, allowed)) {
                vr.addError("BV-15", r.rowNum, sheetNameOf("plating_fee", null),
                        "电镀费用行 " + r.hfPartNo + " 货币代码 " + r.currency + " 不在允许列表: " + allowed);
            }
        }
    }

    /**
     * 错误信息中的 sheet 字段:把物理表名转换为业务 sheet 名,用户更容易识别。
     */
    private static String sheetNameOf(String physicalTable, String discriminator) {
        if (physicalTable == null) return "";
        return switch (physicalTable) {
            case "mat_part" -> "单重";
            case "mat_bom" -> "ELEMENT".equals(discriminator) ? "元素BOM"
                              : "INCOMING".equals(discriminator) ? "来料BOM"
                              : "BOM";
            case "mat_process" -> "组成件BOM及单价";
            case "plating_plan", "mat_plating_plan", "costing_part_plating" -> "电镀方案";
            case "plating_fee", "mat_plating_fee", "costing_part_plating_fee" -> "电镀费用";
            case "mat_customer_part_mapping" -> "客户料号与宏丰料号的关系";
            case "mat_fee" -> switch (discriminator == null ? "" : discriminator) {
                case "INCOMING_FIXED" -> "来料固定加工费";
                case "INCOMING_OTHER" -> "来料其他费用";
                case "FINISHED_FIXED" -> "成品固定加工费";
                case "FINISHED_OTHER" -> "成品其他费用";
                case "INCOMING_ANNUAL_DOWN" -> "来料年降";
                case "ASSEMBLY_PROCESS" -> "组装加工费";
                case "ASSEMBLY_ANNUAL_DOWN" -> "组装加工费年降";
                case "ANNUAL_REDUCTION_FACTOR" -> "年降系数";
                default -> "费用";
            };
            default -> physicalTable;
        };
    }

    /** 单位代码不区分大小写,统一按大写比较 */
    private static boolean isUnitAllowed(String unit, Set<String> allowed) {
        if (unit == null || allowed == null || allowed.isEmpty()) return true;
        String normalized = unit.trim().toUpperCase();
        return allowed.contains(normalized);
    }

    private void checkUnitsInProcess(List<ParsedBasicData.MatProcessRow> rows,
                                      Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.MatProcessRow r : rows) {
            if (r.quantityUnit != null && !isUnitAllowed(r.quantityUnit, allowed)) {
                vr.addError("BV-16", r.rowNum, sheetNameOf("mat_process", null),
                        "料号 " + r.hfPartNo + " 数量单位 " + r.quantityUnit + " 不在允许列表: " + allowed);
            }
        }
    }

    private void checkUnitsInFees(List<ParsedBasicData.MatFeeRow> rows,
                                   Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.MatFeeRow r : rows) {
            if (r.priceUnit != null && !isUnitAllowed(r.priceUnit, allowed)) {
                vr.addError("BV-16", r.rowNum, sheetNameOf("mat_fee", r.feeType),
                        "费用行 " + r.hfPartNo + " 单位 " + r.priceUnit + " 不在允许列表: " + allowed);
            }
        }
    }

    private void checkUnitsInPlatingFees(List<ParsedBasicData.PlatingFeeRow> rows,
                                          Set<String> allowed, ValidationResult vr) {
        for (ParsedBasicData.PlatingFeeRow r : rows) {
            if (r.priceUnit != null && !isUnitAllowed(r.priceUnit, allowed)) {
                vr.addError("BV-16", r.rowNum, sheetNameOf("plating_fee", null),
                        "电镀费用行 " + r.hfPartNo + " 单位 " + r.priceUnit + " 不在允许列表: " + allowed);
            }
        }
    }

    // ── 通用辅助方法 ─────────────────────────────────────────────────────

    private List<String> extractPartNos(ParsedBasicData data) {
        Set<String> partNos = new LinkedHashSet<>();
        for (ParsedBasicData.MatPartRow r : data.matParts) {
            if (r.partNo != null) partNos.add(r.partNo);
        }
        for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
            if (r.hfPartNo != null) partNos.add(r.hfPartNo);
        }
        for (ParsedBasicData.MatFeeRow r : data.matFees) {
            if (r.hfPartNo != null) partNos.add(r.hfPartNo);
        }
        return new ArrayList<>(partNos);
    }

    private Set<String> parseJsonStringSet(String json) {
        if (json == null || json.isBlank()) return new HashSet<>();
        try {
            String[] items = MAPPER.readValue(json, String[].class);
            return new HashSet<>(Arrays.asList(items));
        } catch (Exception e) {
            LOG.warnf("Failed to parse JSON string set: %s", json);
            return new HashSet<>();
        }
    }

    private String serializeErrors(ValidationResult vr) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "errorCount", vr.errors.size(),
                    "warningCount", vr.warnings.size(),
                    "errors", vr.errors.stream()
                            .limit(100)
                            .map(e -> Map.of("bvCode", e.bvCode, "row", e.row,
                                    "sheet", e.sheet, "message", e.message))
                            .collect(Collectors.toList())
            ));
        } catch (Exception e) {
            return "{\"errorCount\":" + vr.errors.size() + "}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 从 resolutions 列表中取指定 tableName + rowKey 的首个非空 note。
     * 多 resolution 同行时取首个非空，符合架构师要求。
     */
    private String firstNonNullNote(List<ResolutionDTO> resolutions, String tableName, String rowKey) {
        if (resolutions == null || resolutions.isEmpty()) return null;
        return resolutions.stream()
                .filter(r -> tableName.equals(r.tableName) && rowKey.equals(r.rowKey)
                        && r.note != null && !r.note.isBlank())
                .map(r -> r.note)
                .findFirst()
                .orElse(null);
    }

    private Boolean parseBool(String v) {
        if (v == null) return null;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "Y".equalsIgnoreCase(v);
    }

    // ── UI-1: 基础资料差异检测 ────────────────────────────────────────────

    /**
     * 检测本次 Excel 中全局表字段与数据库中现有值的差异（mat_part / mat_bom / plating_plan）。
     */
    private List<BasicDataDiffDTO> detectBasicDataDiffs(ParsedBasicData data, UUID customerId) {
        List<BasicDataDiffDTO> diffs = new ArrayList<>();

        // 1. mat_part：batch SELECT by part_no IN (...)
        if (!data.matParts.isEmpty()) {
            List<String> partNos = data.matParts.stream()
                    .map(r -> r.partNo).filter(Objects::nonNull).collect(Collectors.toList());
            if (!partNos.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT part_no, part_name, specification, size_info, " +
                        "  unit_weight, weight_unit, status_code " +
                        "FROM mat_part WHERE part_no IN (:pns)")
                        .setParameter("pns", partNos)
                        .getResultList();
                Map<String, Object[]> dbMap = new HashMap<>();
                for (Object[] r : rows) dbMap.put(str(r[0]), r);

                for (ParsedBasicData.MatPartRow r : data.matParts) {
                    if (r.partNo == null) continue;
                    Object[] db = dbMap.get(r.partNo);
                    if (db == null) continue;  // 新建行，无差异
                    String rowKey = r.partNo;
                    compareField(diffs, "mat_part", rowKey, "part_name",    str(db[1]),  r.partName);
                    compareField(diffs, "mat_part", rowKey, "specification", str(db[2]),  r.specification);
                    compareField(diffs, "mat_part", rowKey, "size_info",     str(db[3]),  r.sizeInfo);
                    compareField(diffs, "mat_part", rowKey, "unit_weight",   str(db[4]),  decStr(r.unitWeight));
                    compareField(diffs, "mat_part", rowKey, "weight_unit",   str(db[5]),  r.weightUnit);
                    compareField(diffs, "mat_part", rowKey, "status_code",   str(db[6]),  r.statusCode);
                }
            }
        }

        // 2. mat_bom：batch SELECT by hf_part_no IN (...)
        if (!data.matBoms.isEmpty()) {
            List<String> bomPartNos = data.matBoms.stream()
                    .map(r -> r.hfPartNo).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!bomPartNos.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT bom_type, hf_part_no, seq_no, input_material_no, element_name, " +
                        "  input_material_name, loss_rate, gross_qty, net_qty, gross_unit, net_unit, " +
                        "  output_material_type, defect_rate, composition_pct " +
                        "FROM mat_bom WHERE hf_part_no IN (:pns)")
                        .setParameter("pns", bomPartNos)
                        .getResultList();
                Map<String, Object[]> dbMap = new HashMap<>();
                for (Object[] r : rows) {
                    String key = str(r[0]) + ":" + str(r[1]) + ":" + str(r[2]) +
                                 ":" + nullStr(r[3]) + ":" + nullStr(r[4]);
                    dbMap.put(key, r);
                }
                // 构建 fuzzy-key 查找 map：(bom_type:hf_part_no:seq_no:COALESCE(element_name,'')) → input_material_no
                // 用于检测 DB 中是否存在同键但 input_material_no IS NULL 的脏数据旧行。
                Map<String, Boolean> fuzzyNullKeyMap = new HashMap<>();
                for (Object[] r : rows) {
                    if ("INCOMING".equals(str(r[0])) && r[3] == null) {
                        // key = bom_type:hf_part_no:seq_no:element_name(coalesce)
                        String fk = str(r[0]) + ":" + str(r[1]) + ":" + str(r[2]) + ":" + (r[4] == null ? "" : str(r[4]));
                        fuzzyNullKeyMap.put(fk, Boolean.TRUE);
                    }
                }

                for (ParsedBasicData.MatBomRow r : data.matBoms) {
                    if (r.hfPartNo == null) continue;
                    String key = r.bomType + ":" + r.hfPartNo + ":" + r.seqNo +
                                 ":" + nullStr(r.inputMaterialNo) + ":" + nullStr(r.elementName);
                    Object[] db = dbMap.get(key);
                    if (db == null) {
                        // 精确键未命中，检测 fuzzy-key 冲突：DB 中是否有同 (INCOMING, hf_part_no, seq_no, element_name) 但 input_material_no IS NULL 的脏行
                        if ("INCOMING".equals(r.bomType) && r.inputMaterialNo != null && !r.inputMaterialNo.isBlank()) {
                            String fk = r.bomType + ":" + r.hfPartNo + ":" + r.seqNo + ":" + (r.elementName == null ? "" : r.elementName);
                            if (fuzzyNullKeyMap.containsKey(fk)) {
                                // 发现脏数据合并场景：DB 有 NULL 占位行，本次 Excel 带来真实值
                                String rowKey = "INCOMING:" + r.hfPartNo + ":" + r.seqNo + ":" + (r.elementName == null ? "" : r.elementName);
                                BasicDataDiffDTO diff = new BasicDataDiffDTO();
                                diff.tableName = "mat_bom";
                                diff.rowKey = rowKey;
                                diff.fieldName = "input_material_no";
                                diff.fieldLabel = "投入料号 (脏数据合并)";
                                diff.oldValue = "(空, 脏数据)";
                                diff.newValue = r.inputMaterialNo;
                                diff.importance = "IMPORTANT";
                                diff.affectsCalculation = false;
                                diffs.add(diff);
                            }
                        }
                        continue;
                    }
                    String rowKey = r.hfPartNo + ":" + r.bomType + ":" + r.seqNo;
                    compareField(diffs, "mat_bom", rowKey, "loss_rate",          str(db[6]),  decStr(r.lossRate));
                    compareField(diffs, "mat_bom", rowKey, "gross_qty",          str(db[7]),  decStr(r.grossQty));
                    compareField(diffs, "mat_bom", rowKey, "net_qty",            str(db[8]),  decStr(r.netQty));
                    compareField(diffs, "mat_bom", rowKey, "defect_rate",        str(db[12]), decStr(r.defectRate));
                    compareField(diffs, "mat_bom", rowKey, "composition_pct",    str(db[13]), decStr(r.compositionPct));
                }
            }
        }

        // 3. plating_plan：batch SELECT by plan_code IN (...)
        if (!data.platingPlans.isEmpty()) {
            List<String> planCodes = data.platingPlans.stream()
                    .map(r -> r.planCode).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!planCodes.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT plan_code, version, seq_no, plating_element, " +
                        "  plating_area, coating_thickness, plating_requirement " +
                        "FROM plating_plan WHERE plan_code IN (:pcs)")
                        .setParameter("pcs", planCodes)
                        .getResultList();
                Map<String, Object[]> dbMap = new HashMap<>();
                for (Object[] r : rows) {
                    dbMap.put(str(r[0]) + ":" + str(r[1]) + ":" + str(r[2]), r);
                }
                for (ParsedBasicData.PlatingPlanRow r : data.platingPlans) {
                    if (r.planCode == null) continue;
                    String key = r.planCode + ":" + r.version + ":" + r.seqNo;
                    Object[] db = dbMap.get(key);
                    if (db == null) continue;
                    String rowKey = r.planCode + ":" + r.version + ":" + r.seqNo;
                    compareField(diffs, "plating_plan", rowKey, "plating_element",     str(db[3]),  r.platingElement);
                    compareField(diffs, "plating_plan", rowKey, "plating_area",        str(db[4]),  decStr(r.platingArea));
                    compareField(diffs, "plating_plan", rowKey, "coating_thickness",   str(db[5]),  decStr(r.coatingThickness));
                    compareField(diffs, "plating_plan", rowKey, "plating_requirement", str(db[6]),  r.platingRequirement);
                }
            }
        }

        return diffs;
    }

    // ── UI-2: 客户资料冲突检测 ────────────────────────────────────────────

    /**
     * 检测本次 Excel 中客户级表字段与数据库当前版本的差异（mat_process / mat_fee / plating_fee）。
     */
    private List<CustomerDataConflictDTO> detectCustomerDataConflicts(ParsedBasicData data, UUID customerId) {
        List<CustomerDataConflictDTO> conflicts = new ArrayList<>();

        // 1. mat_process：查询 is_current=true 版本
        if (!data.matProcesses.isEmpty()) {
            List<String> hfPartNos = data.matProcesses.stream()
                    .map(r -> r.hfPartNo).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!hfPartNos.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT hf_part_no, seq_no, COALESCE(sub_seq_no,-1), " +
                        "  process_code, assembly_process, component_part_no, component_name, " +
                        "  supplier_code, supplier_name, quantity, quantity_unit, " +
                        "  unit_price, freight, currency, price_unit " +
                        "FROM mat_process WHERE customer_id = :cid AND hf_part_no IN (:pns) AND is_current = true")
                        .setParameter("cid", customerId)
                        .setParameter("pns", hfPartNos)
                        .getResultList();
                Map<String, Object[]> dbMap = new HashMap<>();
                for (Object[] r : rows) {
                    dbMap.put(str(r[0]) + ":" + str(r[1]) + ":" + str(r[2]), r);
                }
                for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
                    if (r.hfPartNo == null) continue;
                    String mapKey = r.hfPartNo + ":" + r.seqNo + ":" + (r.subSeqNo != null ? r.subSeqNo : -1);
                    Object[] db = dbMap.get(mapKey);
                    if (db == null) continue;  // 新增行
                    String rowKey = customerId + ":" + r.hfPartNo + ":" + r.seqNo;

                    List<ConflictFieldDTO> fields = new ArrayList<>();
                    addConflictField(fields, "mat_process", "process_code",      str(db[3]),  r.processCode);
                    addConflictField(fields, "mat_process", "assembly_process",  str(db[4]),  r.assemblyProcess);
                    addConflictField(fields, "mat_process", "component_part_no", str(db[5]),  r.componentPartNo);
                    addConflictField(fields, "mat_process", "unit_price",        str(db[11]), decStr(r.unitPrice));
                    addConflictField(fields, "mat_process", "quantity",          str(db[9]),  decStr(r.quantity));
                    addConflictField(fields, "mat_process", "currency",          str(db[13]), r.currency);

                    if (!fields.isEmpty()) {
                        CustomerDataConflictDTO c = new CustomerDataConflictDTO();
                        c.customerId = customerId;
                        c.hfPartNo = r.hfPartNo;
                        c.tableName = "mat_process";
                        c.rowKey = rowKey;
                        c.fields = fields;
                        conflicts.add(c);
                    }
                }
            }
        }

        // 2. mat_fee：查询 is_current=true 版本
        if (!data.matFees.isEmpty()) {
            List<String> hfPartNos = data.matFees.stream()
                    .map(r -> r.hfPartNo).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!hfPartNos.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT hf_part_no, fee_type, seq_no, " +
                        "  fee_value, fee_ratio, currency, price_unit, " +
                        "  settlement_rise_ratio, fixed_rise_value, reject_rate, " +
                        "  COALESCE(dim_input_material_no,''), COALESCE(dim_input_material_name,''), " +
                        "  COALESCE(dim_element_name,''), COALESCE(dim_assembly_process,''), " +
                        "  COALESCE(dim_sub_seq_no::text,'') " +
                        "FROM mat_fee WHERE customer_id = :cid AND hf_part_no IN (:pns) AND is_current = true")
                        .setParameter("cid", customerId)
                        .setParameter("pns", hfPartNos)
                        .getResultList();
                Map<String, Object[]> dbMap = new HashMap<>();
                for (Object[] r : rows) {
                    // V70: dbMap key 必须覆盖所有 dim_* 维度，否则同 (fee_type, seq_no) 多 dim_*
                    // 行会互相覆盖 → 冲突检测拿到错误的对照值
                    String key = matFeeRowKey(str(r[0]), str(r[1]), str(r[2]),
                            str(r[10]), str(r[11]), str(r[12]), str(r[13]), str(r[14]));
                    dbMap.put(key, r);
                }
                for (ParsedBasicData.MatFeeRow r : data.matFees) {
                    if (r.hfPartNo == null) continue;
                    String mapKey = matFeeRowKey(r.hfPartNo, r.feeType, String.valueOf(r.seqNo),
                            nullToEmpty(r.dimInputMaterialNo),
                            nullToEmpty(r.dimInputMaterialName),
                            nullToEmpty(r.dimElementName),
                            nullToEmpty(r.dimAssemblyProcess),
                            r.dimSubSeqNo == null ? "" : String.valueOf(r.dimSubSeqNo));
                    Object[] db = dbMap.get(mapKey);
                    if (db == null) continue;
                    // rowKey 也带上 dim_*，让 KEEP_OLD 决议按完整键回传给 applyResolutionsToParsedData
                    String rowKey = customerId + ":" + r.hfPartNo + ":" + r.feeType + ":" + r.seqNo
                            + ":" + nullToEmpty(r.dimInputMaterialNo)
                            + ":" + nullToEmpty(r.dimInputMaterialName)
                            + ":" + nullToEmpty(r.dimElementName)
                            + ":" + nullToEmpty(r.dimAssemblyProcess)
                            + ":" + (r.dimSubSeqNo == null ? "" : r.dimSubSeqNo);

                    List<ConflictFieldDTO> fields = new ArrayList<>();
                    addConflictField(fields, "mat_fee", "fee_value",             str(db[3]),  decStr(r.feeValue));
                    addConflictField(fields, "mat_fee", "fee_ratio",             str(db[4]),  decStr(r.feeRatio));
                    addConflictField(fields, "mat_fee", "currency",              str(db[5]),  r.currency);
                    addConflictField(fields, "mat_fee", "settlement_rise_ratio", str(db[7]),  decStr(r.settlementRiseRatio));
                    addConflictField(fields, "mat_fee", "reject_rate",           str(db[9]),  decStr(r.rejectRate));

                    if (!fields.isEmpty()) {
                        CustomerDataConflictDTO c = new CustomerDataConflictDTO();
                        c.customerId = customerId;
                        c.hfPartNo = r.hfPartNo;
                        c.tableName = "mat_fee";
                        c.rowKey = rowKey;
                        c.fields = fields;
                        conflicts.add(c);
                    }
                }
            }
        }

        return conflicts;
    }

    // ── R-1: 乐观锁校验（oldValueAtPreview 对比当前 DB 值）──────────────────

    /**
     * 对 ACCEPT_NEW 决策，重新查询 DB 当前值，若与 oldValueAtPreview 不一致抛 409。
     * 数值类型（FieldMeta.comparator=NUM）使用 BigDecimal.compareTo 忽略精度差异。
     */
    private void validateOldValuesOrThrow409(List<ResolutionDTO> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) return;

        for (ResolutionDTO res : resolutions) {
            if (!"ACCEPT_NEW".equals(res.decision)) continue;
            if (res.oldValueAtPreview == null || res.tableName == null
                    || res.rowKey == null || res.fieldName == null) continue;

            String currentDbValue = queryCurrentFieldValue(res.tableName, res.rowKey, res.fieldName);
            if (currentDbValue == null) continue; // 行不存在（新建），跳过

            // 按字段元数据选择比较器
            if (!valuesEqual(res.tableName, res.fieldName,
                    trimToNull(res.oldValueAtPreview), trimToNull(currentDbValue))) {
                throw new BusinessException(409,
                        "字段 " + res.tableName + "." + res.fieldName +
                        " (行=" + res.rowKey + ") 在 preview 到 confirm 期间已被修改，" +
                        "请重新预览后再确认。preview 时旧值=" + res.oldValueAtPreview +
                        "，当前值=" + currentDbValue);
            }
        }
    }

    /**
     * 查询指定表行字段的当前值（仅支持 mat_part）。
     * 扩展：按表名路由。
     */
    private String queryCurrentFieldValue(String tableName, String rowKey, String fieldName) {
        try {
            // 白名单校验（防 SQL 注入）
            if (!isAllowedTable(tableName) || !isAllowedColumn(fieldName)) return null;

            if ("mat_part".equals(tableName)) {
                // rowKey = partNo
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_part WHERE part_no = :pk LIMIT 1")
                        .setParameter("pk", rowKey)
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // mat_bom rowKey = hf_part_no:bom_type:seq_no
            if ("mat_bom".equals(tableName)) {
                String[] parts = rowKey.split(":", 3);
                if (parts.length < 3) return null;
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_bom " +
                        "WHERE hf_part_no = :pn AND bom_type = :bt AND seq_no = CAST(:sn AS INT) LIMIT 1")
                        .setParameter("pn", parts[0])
                        .setParameter("bt", parts[1])
                        .setParameter("sn", parts[2])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // plating_plan rowKey = plan_code:version:seq_no
            if ("plating_plan".equals(tableName)) {
                String[] parts = rowKey.split(":", 3);
                if (parts.length < 3) return null;
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM plating_plan " +
                        "WHERE plan_code = :pc AND version = :v AND seq_no = CAST(:sn AS INT) LIMIT 1")
                        .setParameter("pc", parts[0])
                        .setParameter("v", parts[1])
                        .setParameter("sn", parts[2])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // mat_customer_part_mapping rowKey = customer_id:customer_product_no
            if ("mat_customer_part_mapping".equals(tableName)) {
                int sep = rowKey.indexOf(':');
                if (sep < 0) return null;
                UUID cid = UUID.fromString(rowKey.substring(0, sep));
                String cpno = rowKey.substring(sep + 1);
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_customer_part_mapping " +
                        "WHERE customer_id = :cid AND customer_product_no = :cpno LIMIT 1")
                        .setParameter("cid", cid)
                        .setParameter("cpno", cpno)
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // mat_process rowKey = customer_id:hf_part_no:seq_no
            if ("mat_process".equals(tableName)) {
                String[] parts = rowKey.split(":", 3);
                if (parts.length < 3) return null;
                UUID cid = UUID.fromString(parts[0]);
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_process " +
                        "WHERE customer_id = :cid AND hf_part_no = :pn AND seq_no = CAST(:sn AS INT) " +
                        "  AND is_current = true LIMIT 1")
                        .setParameter("cid", cid)
                        .setParameter("pn", parts[1])
                        .setParameter("sn", parts[2])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // mat_fee rowKey = customer_id:hf_part_no:fee_type:seq_no
            if ("mat_fee".equals(tableName)) {
                String[] parts = rowKey.split(":", 4);
                if (parts.length < 4) return null;
                UUID cid = UUID.fromString(parts[0]);
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_fee " +
                        "WHERE customer_id = :cid AND hf_part_no = :pn AND fee_type = :ft " +
                        "  AND seq_no = CAST(:sn AS INT) AND is_current = true LIMIT 1")
                        .setParameter("cid", cid)
                        .setParameter("pn", parts[1])
                        .setParameter("ft", parts[2])
                        .setParameter("sn", parts[3])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // plating_fee / mat_plating_fee rowKey = customer_id:hf_part_no:plating_plan_code:plan_version
            // V125: 同一 schema, 路由到对应表名.
            if ("plating_fee".equals(tableName) || "mat_plating_fee".equals(tableName)) {
                String[] parts = rowKey.split(":", 4);
                if (parts.length < 4) return null;
                UUID cid = UUID.fromString(parts[0]);
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM " + tableName + " " +
                        "WHERE customer_id = :cid AND hf_part_no = :pn " +
                        "  AND COALESCE(plating_plan_code,'') = COALESCE(:ppc,'') " +
                        "  AND COALESCE(plan_version,'') = COALESCE(:pv,'') " +
                        "  AND is_current = true LIMIT 1")
                        .setParameter("cid", cid)
                        .setParameter("pn", parts[1])
                        .setParameter("ppc", parts[2])
                        .setParameter("pv", parts[3])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
            // costing_part_plating_fee rowKey = hf_part_no:plating_plan_code:plan_version (无 customer)
            if ("costing_part_plating_fee".equals(tableName)) {
                String[] parts = rowKey.split(":", 3);
                if (parts.length < 3) return null;
                Object val = em.createNativeQuery(
                        "SELECT CAST(" + fieldName + " AS TEXT) FROM costing_part_plating_fee " +
                        "WHERE hf_part_no = :pn " +
                        "  AND COALESCE(plating_plan_code,'') = COALESCE(:ppc,'') " +
                        "  AND COALESCE(plan_version,'') = COALESCE(:pv,'') " +
                        "  AND is_active = true LIMIT 1")
                        .setParameter("pn", parts[0])
                        .setParameter("ppc", parts[1])
                        .setParameter("pv", parts[2])
                        .getSingleResult();
                return val == null ? null : val.toString();
            }
        } catch (Exception e) {
            LOG.debugf("queryCurrentFieldValue: %s.%s rowKey=%s → %s", tableName, fieldName, rowKey, e.getMessage());
        }
        return null;
    }

    // ── R-2: CRITICAL 字段必须有 note ────────────────────────────────────

    private void validateCriticalNotes(List<ResolutionDTO> resolutions) {
        if (resolutions == null) return;
        for (ResolutionDTO res : resolutions) {
            if (!"ACCEPT_NEW".equals(res.decision)) continue;
            FieldMetaCache.FieldMeta meta = fieldMetaCache.get(
                    res.tableName != null ? res.tableName : "",
                    res.fieldName != null ? res.fieldName : "");
            if ("CRITICAL".equals(meta.importance())) {
                if (res.note == null || res.note.isBlank()) {
                    throw new BusinessException(400,
                            "CRITICAL 字段 " + res.tableName + "." + res.fieldName +
                            " 选择 ACCEPT_NEW 时必须填写变更说明(note)");
                }
            }
        }
    }

    // ── R-3: 将 KEEP_OLD 决策标记到 ParsedBasicData ──────────────────────

    private void applyResolutionsToParsedData(ParsedBasicData data, List<ResolutionDTO> resolutions) {
        if (resolutions == null) return;
        for (ResolutionDTO res : resolutions) {
            if (!"KEEP_OLD".equals(res.decision)) continue;
            if (res.tableName == null || res.rowKey == null || res.fieldName == null) continue;

            // fuzzy-key 冲突的 KEEP_OLD：rowKey 格式为 "INCOMING:{hfPartNo}:{seqNo}:{elementName}"
            // writePhysicalTables 中 bomRowKey 格式为 "{hfPartNo}:INCOMING:{seqNo}"，需转换后 markSkipRow。
            if ("mat_bom".equals(res.tableName) && "input_material_no".equals(res.fieldName)
                    && res.rowKey.startsWith("INCOMING:")) {
                // 解析 "INCOMING:{hfPartNo}:{seqNo}:{elementName}" → 取 [1] hfPartNo, [2] seqNo
                String[] parts = res.rowKey.split(":", 4);
                if (parts.length >= 3) {
                    String bomRowKey = parts[1] + ":INCOMING:" + parts[2];
                    data.markSkipRow("mat_bom", bomRowKey);
                }
                continue; // 跳过 markSkipField，整行跳过更彻底
            }

            data.markSkipField(res.tableName, res.rowKey, res.fieldName);
        }
    }

    // ── UI-3: 孤儿行检测 ─────────────────────────────────────────────────

    /**
     * 检测 DB 中有但本次 Excel 无的 is_current=true 行（孤儿行）。
     * 仅检测本次 Excel 涉及到的 partNo 范围内（避免全表扫描）。
     * 检测范围：mat_fee + mat_process。
     * mat_part / mat_bom / mat_customer_part_mapping 是单行/料号，不检测孤儿。
     */
    @SuppressWarnings("unchecked")
    private List<com.cpq.importexcel.dto.OrphanRowDTO> detectOrphanRows(ParsedBasicData data, UUID customerId) {
        List<com.cpq.importexcel.dto.OrphanRowDTO> orphans = new ArrayList<>();

        // 收集本次 Excel 涉及的所有 partNo（三张表取并集）
        Set<String> partNosInExcel = new HashSet<>();
        for (var r : data.matFees)     if (r.hfPartNo != null) partNosInExcel.add(r.hfPartNo);
        for (var r : data.matProcesses) if (r.hfPartNo != null) partNosInExcel.add(r.hfPartNo);
        for (var r : data.matBoms)     if (r.hfPartNo != null) partNosInExcel.add(r.hfPartNo);

        if (partNosInExcel.isEmpty()) return orphans;

        // ── mat_fee 孤儿检测 ──────────────────────────────────────────────
        if (!data.matFees.isEmpty()) {
            // 构建 Excel 业务键集合（9 维度）
            Set<String> excelMatFeeKeys = new HashSet<>();
            for (var r : data.matFees) {
                if (r.hfPartNo == null) continue;
                excelMatFeeKeys.add(buildMatFeeOrphanKey(customerId.toString(), r.hfPartNo, r.feeType,
                        String.valueOf(r.seqNo),
                        r.dimInputMaterialNo, r.dimInputMaterialName,
                        r.dimElementName, r.dimAssemblyProcess,
                        r.dimSubSeqNo == null ? null : String.valueOf(r.dimSubSeqNo)));
            }

            List<Object[]> feeRows = em.createNativeQuery(
                    "SELECT customer_id::text, hf_part_no, fee_type, seq_no::text, " +
                    "       dim_input_material_no, dim_input_material_name, dim_element_name, " +
                    "       dim_assembly_process, dim_sub_seq_no::text, " +
                    "       fee_value, fee_ratio, currency, price_unit " +
                    "FROM mat_fee " +
                    "WHERE is_current = true AND customer_id = :cid AND hf_part_no IN (:parts)")
                    .setParameter("cid", customerId)
                    .setParameter("parts", partNosInExcel)
                    .getResultList();

            for (Object[] row : feeRows) {
                String dbKey = buildMatFeeOrphanKey(
                        str(row[0]), str(row[1]), str(row[2]), str(row[3]),
                        (String) row[4], (String) row[5], (String) row[6],
                        (String) row[7], (String) row[8]);
                if (!excelMatFeeKeys.contains(dbKey)) {
                    com.cpq.importexcel.dto.OrphanRowDTO o = new com.cpq.importexcel.dto.OrphanRowDTO();
                    o.tableName = "mat_fee";
                    o.rowKey = dbKey;
                    o.partNo = str(row[1]);
                    o.displayLabel = String.format("%s 项次=%s 投入料号名称=%s 要素=%s (Excel 中无此行)",
                            nullStr(row[2]), nullStr(row[3]), nullStr(row[5]), nullStr(row[6]));
                    o.rowSnapshot = new java.util.LinkedHashMap<>();
                    o.rowSnapshot.put("fee_type", row[2]);
                    o.rowSnapshot.put("seq_no", row[3]);
                    o.rowSnapshot.put("dim_input_material_no", row[4]);
                    o.rowSnapshot.put("dim_input_material_name", row[5]);
                    o.rowSnapshot.put("dim_element_name", row[6]);
                    o.rowSnapshot.put("dim_assembly_process", row[7]);
                    o.rowSnapshot.put("dim_sub_seq_no", row[8]);
                    o.rowSnapshot.put("fee_value", row[9]);
                    o.rowSnapshot.put("fee_ratio", row[10]);
                    o.rowSnapshot.put("currency", row[11]);
                    o.rowSnapshot.put("price_unit", row[12]);
                    o.importance = "NORMAL";
                    orphans.add(o);
                    LOG.debugf("detectOrphanRows: mat_fee orphan detected partNo=%s feeType=%s seq=%s",
                            row[1], row[2], row[3]);
                }
            }
        }

        // ── mat_process 孤儿检测 ──────────────────────────────────────────
        if (!data.matProcesses.isEmpty()) {
            Set<String> excelProcessKeys = new HashSet<>();
            for (var r : data.matProcesses) {
                if (r.hfPartNo == null) continue;
                excelProcessKeys.add(buildMatProcessOrphanKey(customerId.toString(), r.hfPartNo,
                        String.valueOf(r.seqNo),
                        r.subSeqNo == null ? null : String.valueOf(r.subSeqNo)));
            }

            List<Object[]> processRows = em.createNativeQuery(
                    "SELECT customer_id::text, hf_part_no, seq_no::text, " +
                    "       COALESCE(sub_seq_no::text, NULL), " +
                    "       process_code, assembly_process, component_name, unit_price::text " +
                    "FROM mat_process " +
                    "WHERE is_current = true AND customer_id = :cid AND hf_part_no IN (:parts)")
                    .setParameter("cid", customerId)
                    .setParameter("parts", partNosInExcel)
                    .getResultList();

            for (Object[] row : processRows) {
                String dbKey = buildMatProcessOrphanKey(
                        str(row[0]), str(row[1]), str(row[2]), (String) row[3]);
                if (!excelProcessKeys.contains(dbKey)) {
                    com.cpq.importexcel.dto.OrphanRowDTO o = new com.cpq.importexcel.dto.OrphanRowDTO();
                    o.tableName = "mat_process";
                    o.rowKey = dbKey;
                    o.partNo = str(row[1]);
                    o.displayLabel = String.format("工序=%s 组装=%s 组成件=%s (Excel 中无此行)",
                            nullStr(row[4]), nullStr(row[5]), nullStr(row[6]));
                    o.rowSnapshot = new java.util.LinkedHashMap<>();
                    o.rowSnapshot.put("seq_no", row[2]);
                    o.rowSnapshot.put("sub_seq_no", row[3]);
                    o.rowSnapshot.put("process_code", row[4]);
                    o.rowSnapshot.put("assembly_process", row[5]);
                    o.rowSnapshot.put("component_name", row[6]);
                    o.rowSnapshot.put("unit_price", row[7]);
                    o.importance = "NORMAL";
                    orphans.add(o);
                    LOG.debugf("detectOrphanRows: mat_process orphan detected partNo=%s seq=%s subSeq=%s",
                            row[1], row[2], row[3]);
                }
            }
        }

        LOG.infof("detectOrphanRows: customer=%s partNos=%d → %d orphans detected",
                customerId, partNosInExcel.size(), orphans.size());
        return orphans;
    }

    /**
     * mat_fee 孤儿检测业务键：9 维度，NULL → 空串。
     * customer_id:hf_part_no:fee_type:seq_no:dim_input_no:dim_input_name:dim_element:dim_assembly:dim_sub_seq
     */
    private static String buildMatFeeOrphanKey(String customerId, String hfPartNo, String feeType,
                                               String seqNo, String dimInputNo, String dimInputName,
                                               String dimElementName, String dimAssemblyProcess,
                                               String dimSubSeqNo) {
        return customerId + ":" + hfPartNo + ":" + feeType + ":" + seqNo
                + ":" + nullStr2(dimInputNo)
                + ":" + nullStr2(dimInputName)
                + ":" + nullStr2(dimElementName)
                + ":" + nullStr2(dimAssemblyProcess)
                + ":" + nullStr2(dimSubSeqNo);
    }

    /**
     * mat_process 孤儿检测业务键：4 维度，NULL → 空串。
     * customer_id:hf_part_no:seq_no:sub_seq_no
     */
    private static String buildMatProcessOrphanKey(String customerId, String hfPartNo,
                                                   String seqNo, String subSeqNo) {
        return customerId + ":" + hfPartNo + ":" + seqNo + ":" + nullStr2(subSeqNo);
    }

    private static String nullStr2(String s) {
        return s == null ? "" : s;
    }

    // ── R-4: 孤儿行删除（在主事务内，confirm 阶段）──────────────────────

    /**
     * 遍历 resolutions，对 type=ORPHAN_ROW && decision=DELETE_ORPHAN 的项执行物理删除。
     * rowKey 格式：
     *   mat_fee:    customer_id:hf_part_no:fee_type:seq_no:dim_input_no:dim_input_name:dim_element:dim_assembly:dim_sub_seq
     *   mat_process: customer_id:hf_part_no:seq_no:sub_seq_no
     * 必须在 @Transactional doImportInTx 内调用。
     */
    private void deleteOrphans(List<ResolutionDTO> resolutions, UUID customerId) {
        if (resolutions == null || resolutions.isEmpty()) return;
        int deletedFee = 0, deletedProcess = 0;

        for (ResolutionDTO res : resolutions) {
            if (!"ORPHAN_ROW".equals(res.type)) continue;
            if (!"DELETE_ORPHAN".equals(res.decision)) continue;
            if (res.rowKey == null || res.tableName == null) continue;

            if ("mat_fee".equals(res.tableName)) {
                // rowKey = customerId:hfPartNo:feeType:seqNo:dimInputNo:dimInputName:dimElement:dimAssembly:dimSubSeq
                String[] p = res.rowKey.split(":", -1);
                if (p.length < 9) {
                    LOG.warnf("deleteOrphans: mat_fee rowKey split < 9 parts, skip. rowKey=%s", res.rowKey);
                    continue;
                }
                // p[0]=customerId, p[1]=hfPartNo, p[2]=feeType, p[3]=seqNo
                // p[4]=dimInputNo, p[5]=dimInputName, p[6]=dimElement, p[7]=dimAssembly, p[8]=dimSubSeq
                String dimInputNo   = p[4].isEmpty() ? null : p[4];
                String dimInputName = p[5].isEmpty() ? null : p[5];
                String dimElement   = p[6].isEmpty() ? null : p[6];
                String dimAssembly  = p[7].isEmpty() ? null : p[7];
                String dimSubSeq    = p[8].isEmpty() ? null : p[8];

                int n = em.createNativeQuery(
                        "DELETE FROM mat_fee WHERE customer_id = :cid AND hf_part_no = :pn " +
                        "  AND fee_type = :ft AND seq_no = CAST(:sn AS INT) " +
                        "  AND COALESCE(dim_input_material_no,'') = COALESCE(:din,'') " +
                        "  AND COALESCE(dim_input_material_name,'') = COALESCE(:dname,'') " +
                        "  AND COALESCE(dim_element_name,'') = COALESCE(:del,'') " +
                        "  AND COALESCE(dim_assembly_process,'') = COALESCE(:dap,'') " +
                        "  AND COALESCE(dim_sub_seq_no::text,'') = COALESCE(:dss,'') " +
                        "  AND is_current = true")
                        .setParameter("cid", customerId)
                        .setParameter("pn",  p[1])
                        .setParameter("ft",  p[2])
                        .setParameter("sn",  p[3])
                        .setParameter("din", dimInputNo)
                        .setParameter("dname", dimInputName)
                        .setParameter("del", dimElement)
                        .setParameter("dap", dimAssembly)
                        .setParameter("dss", dimSubSeq)
                        .executeUpdate();
                deletedFee += n;
                LOG.infof("deleteOrphans: mat_fee DELETE %d row(s) partNo=%s feeType=%s seq=%s",
                        n, p[1], p[2], p[3]);

            } else if ("mat_process".equals(res.tableName)) {
                // rowKey = customerId:hfPartNo:seqNo:subSeqNo
                String[] p = res.rowKey.split(":", -1);
                if (p.length < 4) {
                    LOG.warnf("deleteOrphans: mat_process rowKey split < 4 parts, skip. rowKey=%s", res.rowKey);
                    continue;
                }
                String subSeq = p[3].isEmpty() ? null : p[3];

                int n = em.createNativeQuery(
                        "DELETE FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn " +
                        "  AND seq_no = CAST(:sn AS INT) " +
                        "  AND COALESCE(sub_seq_no::text,'') = COALESCE(:ss,'') " +
                        "  AND is_current = true")
                        .setParameter("cid", customerId)
                        .setParameter("pn",  p[1])
                        .setParameter("sn",  p[2])
                        .setParameter("ss",  subSeq)
                        .executeUpdate();
                deletedProcess += n;
                LOG.infof("deleteOrphans: mat_process DELETE %d row(s) partNo=%s seq=%s subSeq=%s",
                        n, p[1], p[2], p[3]);
            }
        }

        if (deletedFee + deletedProcess > 0) {
            LOG.infof("deleteOrphans: total deleted mat_fee=%d mat_process=%d", deletedFee, deletedProcess);
        }
    }

    // ── 差异比较辅助方法 ─────────────────────────────────────────────────

    /**
     * 比较字段值（NUM 类用 BigDecimal.compareTo，STR 类用 trimToNull equals）。
     * 不同则创建 BasicDataDiffDTO 加入列表。
     */
    private void compareField(List<BasicDataDiffDTO> diffs, String tableName, String rowKey,
                               String fieldName, String dbVal, String excelVal) {
        if (valuesEqual(tableName, fieldName, dbVal, excelVal)) return;

        FieldMetaCache.FieldMeta meta = fieldMetaCache.get(tableName, fieldName);
        // 诊断日志：用于排查"重复导入同一份 Excel 仍报相同差异"问题。
        // 调试结束后可降级为 trace 或删除。
        LOG.infof("[DIFF] table=%s row=%s field=%s comparator=%s db=[%s] excel=[%s]",
                tableName, rowKey, fieldName, meta.comparator(), dbVal, excelVal);
        BasicDataDiffDTO d = new BasicDataDiffDTO();
        d.tableName = tableName;
        d.rowKey = rowKey;
        d.fieldName = fieldName;
        d.fieldLabel = meta.label();
        d.oldValue = dbVal;
        d.newValue = excelVal;
        d.importance = meta.importance();
        d.affectsCalculation = meta.affectsCalculation();
        diffs.add(d);
    }

    /**
     * 比较字段值，不同则创建 ConflictFieldDTO 加入列表。
     */
    /** mat_fee 完整业务键拼接（含 5 个 dim_* 维度）。 */
    private static String matFeeRowKey(String... parts) {
        return String.join(":", parts);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void addConflictField(List<ConflictFieldDTO> fields, String tableName,
                                   String fieldName, String existingVal, String importVal) {
        if (valuesEqual(tableName, fieldName, existingVal, importVal)) return;

        FieldMetaCache.FieldMeta meta = fieldMetaCache.get(tableName, fieldName);
        ConflictFieldDTO f = new ConflictFieldDTO();
        f.fieldName = fieldName;
        f.fieldLabel = meta.label();
        f.existingValue = existingVal;
        f.importValue = importVal;
        f.importance = meta.importance();
        f.affectsCalculation = meta.affectsCalculation();
        fields.add(f);
    }

    /**
     * 根据比较器类型判断两值是否相等（忽略精度差异）。
     */
    private boolean valuesEqual(String tableName, String fieldName, String v1, String v2) {
        FieldMetaCache.FieldMeta meta = fieldMetaCache.get(tableName, fieldName);
        String t1 = trimToNull(v1);
        String t2 = trimToNull(v2);
        if (t1 == null && t2 == null) return true;
        // Excel 端（v2）为空 → writePhysicalTables 用 COALESCE(:param, column) 保留 DB 值，
        // 不会产生任何写入。所以这种"DB 有值、Excel 缺列"的情况不应作为可确认差异
        // 暴露给用户（否则同一份 Excel 复导入会反复要求确认相同条目）。
        if (t2 == null) return true;
        if (t1 == null) return false;

        if ("NUM".equals(meta.comparator())) {
            try {
                return new BigDecimal(t1).compareTo(new BigDecimal(t2)) == 0;
            } catch (NumberFormatException e) {
                // fall through to STR
            }
        }
        return t1.equals(t2);
    }

    /** BigDecimal to plain string (no trailing zeros for display) */
    private String decStr(BigDecimal d) {
        if (d == null) return null;
        return d.stripTrailingZeros().toPlainString();
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String nullStr(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ── Resolution serialization ─────────────────────────────────────────

    private String serializeResolutions(List<ResolutionDTO> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) return "null";
        try {
            return MAPPER.writeValueAsString(Map.of("resolutions", resolutions));
        } catch (Exception e) {
            LOG.warnf("Failed to serialize resolutions: %s", e.getMessage());
            return "null";
        }
    }

    // ── 白名单安全辅助 ───────────────────────────────────────────────────

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "mat_part", "mat_bom", "plating_plan", "mat_customer_part_mapping",
            "mat_process", "mat_fee", "plating_fee",
            // V125: 双侧分流后增加的电镀表
            "mat_plating_plan", "mat_plating_fee", "costing_part_plating_fee");

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "part_name", "specification", "size_info", "unit_weight", "weight_unit", "status_code",
            "input_material_name", "loss_rate", "gross_qty", "net_qty", "gross_unit", "net_unit",
            "output_material_type", "defect_rate", "composition_pct",
            "plating_element", "plating_area", "coating_thickness", "plating_requirement",
            "process_code", "assembly_process", "component_part_no", "component_name",
            "supplier_code", "supplier_name", "quantity", "quantity_unit",
            "unit_price", "freight", "currency", "price_unit",
            "fee_value", "fee_ratio", "settlement_rise_ratio", "fixed_rise_value", "reject_rate",
            "plating_process_fee", "plating_material_fee", "hf_part_no",
            "customer_part_name", "customer_drawing_no", "payment_method",
            "base_currency", "quote_currency");

    private boolean isAllowedTable(String t) {
        return t != null && ALLOWED_TABLES.contains(t.toLowerCase());
    }

    private boolean isAllowedColumn(String c) {
        return c != null && ALLOWED_COLUMNS.contains(c.toLowerCase());
    }

    /**
     * B1: 检测本次 Excel 涉及的 (customer_product_no, hf_part_no) 集合,
     * 为每个料号生成 PartVersionPreviewDTO. 默认 action=BUMP, suggestedNewVersion=current+1.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<com.cpq.partversion.dto.PartVersionPreviewDTO> detectPartVersionPreview(
            com.cpq.importexcel.parser.ParsedBasicData data, java.util.UUID customerId) {
        // 1. 聚合所有 hfPartNo (去重)
        java.util.Set<String> hfPartNos = new java.util.HashSet<>();
        for (var r : data.matBoms) if (r.hfPartNo != null && !r.hfPartNo.isBlank()) hfPartNos.add(r.hfPartNo);
        for (var r : data.matProcesses) if (r.hfPartNo != null && !r.hfPartNo.isBlank()) hfPartNos.add(r.hfPartNo);
        for (var r : data.matFees) if (r.hfPartNo != null && !r.hfPartNo.isBlank()) hfPartNos.add(r.hfPartNo);
        for (var r : data.platingFees) if (r.hfPartNo != null && !r.hfPartNo.isBlank()) hfPartNos.add(r.hfPartNo);
        for (var r : data.mappings) if (r.hfPartNo != null && !r.hfPartNo.isBlank()) hfPartNos.add(r.hfPartNo);
        for (var r : data.costingPartRows) {
            String hf = r.values != null ? r.values.get("hf_part_no") : null;
            if (hf != null && !hf.isBlank()) hfPartNos.add(hf);
        }

        if (hfPartNos.isEmpty()) return java.util.List.of();

        // 2. 查 mapping 获取 (cpn, hf, current_version)
        java.util.List<Object[]> rows = em.createNativeQuery(
                "SELECT customer_product_no, hf_part_no, current_version " +
                "FROM mat_customer_part_mapping " +
                "WHERE customer_id = :cid " +
                "  AND hf_part_no IN :hfs " +
                "  AND customer_product_no IS NOT NULL")
                .setParameter("cid", customerId)
                .setParameter("hfs", hfPartNos)
                .getResultList();

        // 3. 组装 (cpn, hf) → DTO
        java.util.List<com.cpq.partversion.dto.PartVersionPreviewDTO> result = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            String cpn = r[0] == null ? null : r[0].toString();
            String hf = r[1] == null ? null : r[1].toString();
            int currentVer = r[2] == null ? 2000 : ((Number) r[2]).intValue();
            if (cpn != null && hf != null) {
                result.add(com.cpq.partversion.dto.PartVersionPreviewDTO.forBump(cpn, hf, currentVer));
            }
        }
        return result;
    }
}
