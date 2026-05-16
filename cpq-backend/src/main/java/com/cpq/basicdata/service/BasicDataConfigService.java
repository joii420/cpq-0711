package com.cpq.basicdata.service;

import com.cpq.basicdata.dto.*;
import com.cpq.basicdata.entity.BasicDataAttribute;
import com.cpq.basicdata.entity.BasicDataConfig;
import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import com.cpq.masterdata.registry.TableRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class BasicDataConfigService {

    private static final Logger LOG = Logger.getLogger(BasicDataConfigService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    TableRegistry tableRegistry;

    /**
     * 写操作后通知 BasicDataImportServiceV5 重载进程内的 sheetConfigCache / sheetAttributeCache。
     * 用 Instance<> 避免 Quarkus CDI 早期初始化时形成强依赖（importexcel 包 → basicdata 包 entity 已经依赖一次）。
     */
    @Inject
    Instance<BasicDataImportServiceV5> importServiceInstance;

    /**
     * 任何 sheet / attribute / derived 写操作后调用，确保 Excel 导入校验读到最新的 is_required / column_letter / data_type 等元数据。
     * 失败不抛异常 — cache 重载失败只影响下一次导入校验（用户重启 Quarkus 也能恢复），不应阻塞写操作本身的事务提交。
     */
    private void invalidateImportCache() {
        try {
            importServiceInstance.get().reloadConfigCache();
        } catch (Exception e) {
            LOG.warnf("Failed to reload BasicDataImportServiceV5 cache after metadata write: %s", e.getMessage());
        }
    }

    // ========== Sheet 配置 ==========

    public List<BasicDataConfigDTO> listSheets(String status) {
        return listSheets(status, null);
    }

    /**
     * V79：增 templateKind 过滤参数。
     *   templateKind = 'QUOTATION' → 命中 QUOTATION 或 BOTH 的 sheet
     *   templateKind = 'COSTING'   → 命中 COSTING   或 BOTH 的 sheet
     *   templateKind = null        → 不过滤（全部）
     * 让组件 PathPickerDrawer 按"报价/核价"做模板范围过滤。
     */
    public List<BasicDataConfigDTO> listSheets(String status, String templateKind) {
        StringBuilder hql = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            hql.append(" AND status = ?").append(params.size() + 1);
            params.add(status);
        }
        if (templateKind != null && !templateKind.isBlank()) {
            // BOTH 默认两类都能看到
            hql.append(" AND (templateKind = ?").append(params.size() + 1).append(" OR templateKind = 'BOTH')");
            params.add(templateKind);
        }
        hql.append(" ORDER BY sortOrder, sheetIndex, sheetName");
        List<BasicDataConfig> rows = BasicDataConfig.find(hql.toString(), params.toArray()).list();
        return rows.stream().map(BasicDataConfigDTO::from).collect(Collectors.toList());
    }

    public BasicDataConfigDTO getSheet(UUID id) {
        BasicDataConfig c = BasicDataConfig.findById(id);
        if (c == null) throw new BusinessException(404, "Sheet config not found: " + id);
        return BasicDataConfigDTO.from(c);
    }

    @Transactional
    public BasicDataConfigDTO createSheet(CreateBasicDataConfigRequest req) {
        long dup = BasicDataConfig.count("sheetName = ?1 AND status = 'ACTIVE'", req.sheetName);
        if (dup > 0) throw new BusinessException(400, "Sheet name already exists: " + req.sheetName);

        BasicDataConfig c = new BasicDataConfig();
        c.sheetName = req.sheetName;
        if (req.sheetIndex != null) c.sheetIndex = req.sheetIndex;
        if (req.headerRowIndex != null) c.headerRowIndex = req.headerRowIndex;
        if (req.dataStartRowIndex != null) c.dataStartRowIndex = req.dataStartRowIndex;
        c.description = req.description;
        c.parentConfigId = req.parentConfigId;
        c.joinColumns = req.joinColumns != null ? toJson(req.joinColumns) : "[]";
        if (req.sortOrder != null) c.sortOrder = req.sortOrder;
        if (req.status != null) c.status = req.status;
        // V58: target_table / target_discriminator
        if (req.targetTable != null) c.targetTable = req.targetTable;
        if (req.targetDiscriminator != null) c.targetDiscriminator = toJson(req.targetDiscriminator);
        // V79: template_kind
        if (req.templateKind != null && !req.templateKind.isBlank()) c.templateKind = req.templateKind;
        c.persist();

        LOG.infof("Created basic data sheet config: %s id=%s", c.sheetName, c.id);
        invalidateImportCache();
        return BasicDataConfigDTO.from(c);
    }

    @Transactional
    public BasicDataConfigDTO updateSheet(UUID id, CreateBasicDataConfigRequest req) {
        BasicDataConfig c = BasicDataConfig.findById(id);
        if (c == null) throw new BusinessException(404, "Sheet config not found: " + id);

        if (req.sheetName != null && !req.sheetName.equals(c.sheetName)) {
            long dup = BasicDataConfig.count("sheetName = ?1 AND id != ?2 AND status = 'ACTIVE'", req.sheetName, id);
            if (dup > 0) throw new BusinessException(400, "Sheet name already exists: " + req.sheetName);
            c.sheetName = req.sheetName;
        }
        if (req.sheetIndex != null) c.sheetIndex = req.sheetIndex;
        if (req.headerRowIndex != null) c.headerRowIndex = req.headerRowIndex;
        if (req.dataStartRowIndex != null) c.dataStartRowIndex = req.dataStartRowIndex;
        if (req.description != null) c.description = req.description;
        if (req.parentConfigId != null) {
            if (req.parentConfigId.equals(id)) throw new BusinessException(400, "Cannot set self as parent");
            c.parentConfigId = req.parentConfigId;
        }
        if (req.joinColumns != null) c.joinColumns = toJson(req.joinColumns);
        if (req.sortOrder != null) c.sortOrder = req.sortOrder;
        if (req.status != null) c.status = req.status;
        // V58: target_table / target_discriminator（允许前端显式传 null 清空）
        if (req.targetTable != null) c.targetTable = req.targetTable;
        if (req.targetDiscriminator != null) c.targetDiscriminator = toJson(req.targetDiscriminator);
        // V79: template_kind
        if (req.templateKind != null && !req.templateKind.isBlank()) c.templateKind = req.templateKind;

        invalidateImportCache();
        return BasicDataConfigDTO.from(c);
    }

    @Transactional
    public void deleteSheet(UUID id) {
        BasicDataConfig c = BasicDataConfig.findById(id);
        if (c == null) throw new BusinessException(404, "Sheet config not found: " + id);

        long children = BasicDataConfig.count("parentConfigId = ?1", id);
        if (children > 0) throw new BusinessException(400, "Cannot delete: has child sheets");

        // Cascade: attribute & derived attribute deletion via FK ON DELETE CASCADE
        c.delete();
        LOG.infof("Deleted basic data sheet id=%s", id);
        invalidateImportCache();
    }

    // ========== Attributes ==========

    public List<BasicDataAttributeDTO> listAttributes(UUID sheetId, String status) {
        if (sheetId == null) throw new BusinessException(400, "sheetId is required");
        String hql = (status == null || status.isBlank())
                ? "configId = ?1 ORDER BY sortOrder, columnLetter"
                : "configId = ?1 AND status = ?2 ORDER BY sortOrder, columnLetter";
        List<BasicDataAttribute> rows = (status == null || status.isBlank())
                ? BasicDataAttribute.find(hql, sheetId).list()
                : BasicDataAttribute.find(hql, sheetId, status).list();
        return rows.stream().map(BasicDataAttributeDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public BasicDataAttributeDTO createAttribute(CreateBasicDataAttributeRequest req) {
        if (req.configId == null) throw new BusinessException(400, "configId is required");
        if (BasicDataConfig.findById(req.configId) == null)
            throw new BusinessException(400, "Sheet config not found: " + req.configId);
        long dup = BasicDataAttribute.count("variableCode = ?1", req.variableCode);
        long dupDerived = DerivedAttribute.count("variableCode = ?1", req.variableCode);
        if (dup > 0 || dupDerived > 0)
            throw new BusinessException(400, "variableCode already exists: " + req.variableCode);

        BasicDataAttribute a = new BasicDataAttribute();
        a.configId = req.configId;
        a.columnLetter = req.columnLetter;
        a.columnTitle = req.columnTitle;
        a.variableCode = req.variableCode;
        a.variableLabel = req.variableLabel;
        if (req.dataType != null) a.dataType = req.dataType;
        if (req.status != null) a.status = req.status;
        if (req.sortOrder != null) a.sortOrder = req.sortOrder;
        // D-5：写入重要性字段
        if (req.importanceLevel != null) {
            validateImportanceLevel(req.importanceLevel);
            a.importanceLevel = req.importanceLevel;
        }
        if (req.affectsCalculation != null) a.affectsCalculation = req.affectsCalculation;
        // V58: is_required
        if (req.isRequired != null) a.isRequired = req.isRequired;
        a.persist();
        invalidateImportCache();
        return BasicDataAttributeDTO.from(a);
    }

    @Transactional
    public BasicDataAttributeDTO updateAttribute(UUID id, CreateBasicDataAttributeRequest req) {
        BasicDataAttribute a = BasicDataAttribute.findById(id);
        if (a == null) throw new BusinessException(404, "Attribute not found: " + id);

        if (req.variableCode != null && !req.variableCode.equals(a.variableCode)) {
            long dup = BasicDataAttribute.count("variableCode = ?1 AND id != ?2", req.variableCode, id);
            long dupDerived = DerivedAttribute.count("variableCode = ?1", req.variableCode);
            if (dup > 0 || dupDerived > 0)
                throw new BusinessException(400, "variableCode already exists: " + req.variableCode);
            a.variableCode = req.variableCode;
        }
        if (req.columnLetter != null) a.columnLetter = req.columnLetter;
        if (req.columnTitle != null) a.columnTitle = req.columnTitle;
        if (req.variableLabel != null) a.variableLabel = req.variableLabel;
        if (req.dataType != null) a.dataType = req.dataType;
        if (req.status != null) a.status = req.status;
        if (req.sortOrder != null) a.sortOrder = req.sortOrder;
        // D-5：更新重要性字段
        if (req.importanceLevel != null) {
            validateImportanceLevel(req.importanceLevel);
            a.importanceLevel = req.importanceLevel;
        }
        if (req.affectsCalculation != null) a.affectsCalculation = req.affectsCalculation;
        // V58: is_required
        if (req.isRequired != null) a.isRequired = req.isRequired;

        invalidateImportCache();
        return BasicDataAttributeDTO.from(a);
    }

    @Transactional
    public void disableAttribute(UUID id) {
        BasicDataAttribute a = BasicDataAttribute.findById(id);
        if (a == null) throw new BusinessException(404, "Attribute not found: " + id);
        a.status = "DISABLED";  // 禁用替代删除
        invalidateImportCache();
    }

    /**
     * D-5：专用端点 — 仅更新字段重要性 + affects_calculation（SYSTEM_ADMIN 专属）。
     *
     * @param id               BasicDataAttribute 主键
     * @param importanceLevel  CRITICAL / IMPORTANT / NORMAL（null 则不更新）
     * @param affectsCalculation 是否触发公式重算（null 则不更新）
     * @return 更新后的 DTO
     */
    @Transactional
    public BasicDataAttributeDTO updateAttributeImportance(UUID id, String importanceLevel, Boolean affectsCalculation) {
        BasicDataAttribute a = BasicDataAttribute.findById(id);
        if (a == null) throw new BusinessException(404, "Attribute not found: " + id);

        if (importanceLevel != null) {
            validateImportanceLevel(importanceLevel);
            a.importanceLevel = importanceLevel;
        }
        if (affectsCalculation != null) {
            a.affectsCalculation = affectsCalculation;
        }

        LOG.infof("Updated importance for attribute id=%s importanceLevel=%s affectsCalculation=%s",
                id, a.importanceLevel, a.affectsCalculation);
        invalidateImportCache();
        return BasicDataAttributeDTO.from(a);
    }

    /**
     * 校验 importanceLevel 枚举值。
     */
    private static final java.util.Set<String> VALID_IMPORTANCE_LEVELS =
            java.util.Set.of("CRITICAL", "IMPORTANT", "NORMAL");

    private void validateImportanceLevel(String level) {
        if (!VALID_IMPORTANCE_LEVELS.contains(level)) {
            throw new BusinessException(400,
                    "importanceLevel 无效: " + level + "，允许值: CRITICAL / IMPORTANT / NORMAL");
        }
    }

    // ========== Derived Attributes ==========

    public List<DerivedAttributeDTO> listDerived(UUID sheetId, String status) {
        if (sheetId == null) throw new BusinessException(400, "sheetId is required");
        String hql = (status == null || status.isBlank())
                ? "hostSheetId = ?1 ORDER BY sortOrder, variableCode"
                : "hostSheetId = ?1 AND status = ?2 ORDER BY sortOrder, variableCode";
        List<DerivedAttribute> rows = (status == null || status.isBlank())
                ? DerivedAttribute.find(hql, sheetId).list()
                : DerivedAttribute.find(hql, sheetId, status).list();
        return rows.stream().map(DerivedAttributeDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public DerivedAttributeDTO createDerived(CreateDerivedAttributeRequest req) {
        if (req.hostSheetId == null) throw new BusinessException(400, "hostSheetId is required");
        if (BasicDataConfig.findById(req.hostSheetId) == null)
            throw new BusinessException(400, "Sheet config not found: " + req.hostSheetId);
        long dup = DerivedAttribute.count("variableCode = ?1", req.variableCode);
        long dupAttr = BasicDataAttribute.count("variableCode = ?1", req.variableCode);
        if (dup > 0 || dupAttr > 0)
            throw new BusinessException(400, "variableCode already exists: " + req.variableCode);

        validateComputationReferences(req.hostSheetId, req.computationType, req.computation, null);

        DerivedAttribute d = new DerivedAttribute();
        d.hostSheetId = req.hostSheetId;
        d.variableCode = req.variableCode;
        d.variableLabel = req.variableLabel;
        if (req.dataType != null) d.dataType = req.dataType;
        d.computationType = req.computationType;
        d.computation = toJson(req.computation);
        if (req.status != null) d.status = req.status;
        if (req.sortOrder != null) d.sortOrder = req.sortOrder;
        d.persist();
        invalidateImportCache();
        return DerivedAttributeDTO.from(d);
    }

    @Transactional
    public DerivedAttributeDTO updateDerived(UUID id, CreateDerivedAttributeRequest req) {
        DerivedAttribute d = DerivedAttribute.findById(id);
        if (d == null) throw new BusinessException(404, "Derived attribute not found: " + id);

        if (req.variableCode != null && !req.variableCode.equals(d.variableCode)) {
            long dup = DerivedAttribute.count("variableCode = ?1 AND id != ?2", req.variableCode, id);
            long dupAttr = BasicDataAttribute.count("variableCode = ?1", req.variableCode);
            if (dup > 0 || dupAttr > 0)
                throw new BusinessException(400, "variableCode already exists: " + req.variableCode);
            d.variableCode = req.variableCode;
        }
        if (req.variableLabel != null) d.variableLabel = req.variableLabel;
        if (req.dataType != null) d.dataType = req.dataType;
        if (req.computationType != null) d.computationType = req.computationType;
        if (req.computation != null) {
            String type = req.computationType != null ? req.computationType : d.computationType;
            validateComputationReferences(d.hostSheetId, type, req.computation, d.id);
            d.computation = toJson(req.computation);
        }
        if (req.status != null) d.status = req.status;
        if (req.sortOrder != null) d.sortOrder = req.sortOrder;

        invalidateImportCache();
        return DerivedAttributeDTO.from(d);
    }

    /**
     * Validate that EXPRESSION formulas only reference columns/derived fields known on the
     * host sheet. {@code [colName]} tokens are checked against:
     *   - sibling BasicDataAttribute.variableLabel / variableCode on the same sheet
     *   - other DerivedAttribute.variableCode / variableLabel on the same sheet (excluding self)
     *
     * <p>{@code variable_path} {VAR} tokens are intentionally NOT validated here — they may
     * span sheets and are resolved at runtime via DataPathResolver.
     */
    @SuppressWarnings("unchecked")
    private void validateComputationReferences(UUID hostSheetId, String type, Object computation, UUID excludeId) {
        if (computation == null || !"EXPRESSION".equals(type)) return;

        String formula = null;
        try {
            if (computation instanceof java.util.Map) {
                Object f = ((java.util.Map<String, Object>) computation).get("formula");
                if (f != null) formula = f.toString();
            } else if (computation instanceof String s && !s.isBlank()) {
                java.util.Map<String, Object> m = MAPPER.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                if (m.get("formula") != null) formula = m.get("formula").toString();
            }
        } catch (Exception ignore) {
            return;  // can't parse — skip validation, runtime engine will surface error
        }
        if (formula == null || formula.isBlank()) return;

        java.util.Set<String> tokens = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]]+)]").matcher(formula);
        while (m.find()) tokens.add(m.group(1).trim());
        if (tokens.isEmpty()) return;

        java.util.Set<String> known = new java.util.HashSet<>();
        for (BasicDataAttribute a : BasicDataAttribute.<BasicDataAttribute>list("configId = ?1", hostSheetId)) {
            if (a.variableCode != null) known.add(a.variableCode);
            if (a.variableLabel != null) known.add(a.variableLabel);
            if (a.columnTitle != null) known.add(a.columnTitle);
        }
        for (DerivedAttribute d : DerivedAttribute.<DerivedAttribute>list("hostSheetId = ?1", hostSheetId)) {
            if (excludeId != null && excludeId.equals(d.id)) continue;
            if (d.variableCode != null) known.add(d.variableCode);
            if (d.variableLabel != null) known.add(d.variableLabel);
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String t : tokens) if (!known.contains(t)) missing.add(t);
        if (!missing.isEmpty()) {
            throw new BusinessException(400,
                    "EXPRESSION 公式引用了未知字段: " + String.join(", ", missing));
        }
    }

    @Transactional
    public void disableDerived(UUID id) {
        DerivedAttribute d = DerivedAttribute.findById(id);
        if (d == null) throw new BusinessException(404, "Derived attribute not found: " + id);
        d.status = "DISABLED";
        invalidateImportCache();
    }

    // ========== Excel 解析 ==========

    public ParsedExcelStructureDTO parseExcel(InputStream is) {
        ParsedExcelStructureDTO result = new ParsedExcelStructureDTO();
        result.sheets = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(is)) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                ParsedExcelStructureDTO.ParsedSheetDTO s = new ParsedExcelStructureDTO.ParsedSheetDTO();
                s.sheetIndex = si;
                s.sheetName = sheet.getSheetName();
                s.headerRowIndex = 1;
                s.columns = new ArrayList<>();
                Row headerRow = sheet.getRow(0);
                if (headerRow != null) {
                    int last = headerRow.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        Cell cell = headerRow.getCell(c);
                        String title = cell == null ? "" : safeStr(cell);
                        if (title == null || title.isBlank()) continue;
                        ParsedExcelStructureDTO.ParsedColumnDTO col = new ParsedExcelStructureDTO.ParsedColumnDTO();
                        col.columnIndex = c;
                        col.columnLetter = CellReference.convertNumToColString(c);
                        col.columnTitle = title.trim();
                        s.columns.add(col);
                    }
                }
                result.sheets.add(s);
            }
        } catch (Exception e) {
            throw new BusinessException(400, "Failed to parse Excel: " + e.getMessage());
        }
        return result;
    }

    private String safeStr(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    // ========== 可选物理表清单（for target_table 下拉） ==========

    /**
     * V58 辅助 — 返回 TableRegistry 全部表的摘要，供前端下拉选择 target_table。
     */
    public List<ExtensibleTableDTO> listExtensibleTables() {
        return tableRegistry.all().stream()
                .map(m -> new ExtensibleTableDTO(m.tableName(), m.displayName(), m.customerScoped(), m.group()))
                .collect(Collectors.toList());
    }

    /** 可选物理表摘要 DTO（内嵌 record，便于 JSON 序列化）。 */
    public record ExtensibleTableDTO(
            String tableName,
            String displayName,
            boolean customerScoped,
            String group
    ) {}

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
