package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComponentService {

    private static final Logger LOG = Logger.getLogger(ComponentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 用户可录入的字段类型集合（含可编辑字段的多行 driver 组件须声明 rowKeyFields）。
     * 对应 docs/组件管理字段配置指南.md §二 "用户输入" 类别。
     */
    private static final Set<String> EDITABLE_FIELD_TYPES =
        Set.of("INPUT_NUMBER", "INPUT_TEXT", "LIST_FORMULA");

    private static final Set<String> VALID_FIELD_TYPES = Set.of(
        "FIXED_VALUE", "DATA_SOURCE", "INPUT", "INPUT_TEXT", "INPUT_NUMBER", "FORMULA",
        "BASIC_DATA",  // V5: BNF 路径绑定基础数据物理表(对应前端 PathPickerDrawer)
        "LIST_FORMULA" // V203/Phase B: 配置模板驱动 + IF-ELSE-IF 条件分支公式
    );

    public static final java.util.Set<String> VALID_COMPONENT_TYPES =
        java.util.Set.of("NORMAL", "SUBTOTAL", "EXCEL");

    /**
     * task-0721 B4：页签类型属性值域（5 类，需求说明 §4.3 规则一）。
     * BOM=树状页签(结构角色)；材质元素/零件/外购件=对应 characteristic 三态；主件=成品/树根。
     */
    public static final java.util.Set<String> VALID_TAB_TYPES =
        java.util.Set.of("BOM", "材质元素", "零件", "外购件", "主件");

    /** tabType 非法值 → 400；null/blank 视为未配置，放行。 */
    public static void assertValidTabType(String tabType) {
        if (tabType == null || tabType.isBlank()) return;
        if (!VALID_TAB_TYPES.contains(tabType)) {
            throw new BusinessException(400, "Invalid tabType: " + tabType +
                ". Must be one of: " + VALID_TAB_TYPES);
        }
    }

    /**
     * task-0721（2026-07-21 补录，2026-07-23 放宽）：这 4 类 tabType 是"物料语义"页签，类型判定
     * （{@code BomNodeTypeResolver}）与加叶子候选料号采集依赖其"标识列"取值，故保存期强制要求
     * {@code partNoField} 或 {@code partNameField} 至少配一个。{@code BOM}（树页签）料件标识取系统列
     * {@code __hfPartNo}，不在此列。
     *
     * <p>**2026-07-23 修订背景**：部分页签（如「外购件/费用」类）没有料号列，只用「料件名称」
     * （如「组成件1」）做标识，此前"必须配 part_no_field"过严会把这类页签堵死。
     */
    private static final java.util.Set<String> TAB_TYPES_REQUIRE_PART_NO_FIELD =
        java.util.Set.of("材质元素", "零件", "外购件", "主件");

    /**
     * task-0721 B4：页签类型属性写入编排。{@code requestedTabType == null} → tabType 本身不变（既有
     * {@code bomRecursiveExpand} 手动设置保留），但仍按【当前生效的 tabType + 本次合并后的
     * partNoField/partNameField】校验标识列要求（见 {@link #assertPartNoFieldRequirement}）。
     * {@code requestedTabType} 非 null 时：① 值域校验；② {@code tabType="BOM"} 时先跑 COSTING 模板
     * 反向护栏（见 {@link #assertNotReferencedByCostingTemplate}）；③ 与 {@code bomRecursiveExpand}
     * 自动同步（2026-07-21 裁决 Q1："BOM"→true，其他值→false；仅是兼容既有 UI/查询的实现细节，
     * <b>不参与</b>报价侧树渲染路由判断——路由判据是 {@link BomTreeRenderService#isQuoteTreeTabType}）。
     *
     * @param requestedPartNoField   非 null → 覆盖 {@code component.partNoField}（空串=清空）
     * @param requestedPartNameField 非 null → 覆盖 {@code component.partNameField}（空串=清空）
     */
    private void applyTabType(Component component, String requestedTabType,
                              String requestedPartNoField, String requestedPartNameField) {
        if (requestedPartNoField != null) {
            component.partNoField = requestedPartNoField.isBlank() ? null : requestedPartNoField;
        }
        if (requestedPartNameField != null) {
            component.partNameField = requestedPartNameField.isBlank() ? null : requestedPartNameField;
        }

        if (requestedTabType != null) {
            assertValidTabType(requestedTabType);
            String normalized = requestedTabType.isBlank() ? null : requestedTabType;
            if (BomTreeRenderService.isQuoteTreeTabType(normalized)) {
                assertNotReferencedByCostingTemplate(component.id);
                component.bomRecursiveExpand = Boolean.TRUE;
            } else {
                component.bomRecursiveExpand = Boolean.FALSE;
            }
            component.tabType = normalized;
        }

        assertPartNoFieldRequirement(component.tabType, component.partNoField, component.partNameField);
    }

    /**
     * task-0721（2026-07-21 补录，2026-07-23 放宽为"料号列或名称列至少一个"）：
     * {@code tabType ∈ {材质元素,零件,外购件,主件}} 但 {@code partNoField}/{@code partNameField}
     * 均缺 → 400（api.md §1 / 需求说明 §4.3 规则一 2026-07-23 修订）。校验对象是【本次保存后生效的
     * 最终状态】，而非仅本次请求携带的字段——即便本次请求只改了别的字段、未碰 tabType/两个标识列，
     * 只要合并后仍处于"要求标识列但两者皆缺"的非法状态就拦，不放过存量脏数据继续演化。
     */
    private static void assertPartNoFieldRequirement(String tabType, String partNoField, String partNameField) {
        if (tabType == null || !TAB_TYPES_REQUIRE_PART_NO_FIELD.contains(tabType)) return;
        boolean noField = partNoField == null || partNoField.isBlank();
        boolean noNameField = partNameField == null || partNameField.isBlank();
        if (noField && noNameField) {
            throw new BusinessException(400,
                "tabType=" + tabType + " 类型页签必须配置料号列或名称列至少一个作为匹配标识，否则该页签无法参与类型判定匹配");
        }
    }

    /**
     * task-0721 B4 强制护栏（2026-07-21 业务方裁决）：{@code bomRecursiveExpand} 是组件级全局开关，
     * 同一组件被多模板共用时一开全生效。现网实查：3 个开启该开关的组件
     * （COMP-0021__imp1__imp1 / COMP-0039 / COMP-0042）共 34 处模板引用，<b>全部在 COSTING 模板</b>。
     *
     * <p>若该组件已被<b>任一</b> COSTING（{@code template.templateKind='COSTING'}）模板引用，
     * 禁止把 {@code tabType} 改为 {@code BOM}（树渲染）——否则会把这些核价模板一并改成树渲染，
     * 直接违反 AC-10 核价零回归门禁。报价侧树页签应<b>新建专用组件</b>，不复用核价侧已有树组件。
     */
    private void assertNotReferencedByCostingTemplate(UUID componentId) {
        if (componentId == null) return; // 新建流程尚无 id，不存在既有模板引用，护栏天然不触发
        Number count = (Number) em.createNativeQuery(
                "SELECT count(*) FROM template_component tc " +
                "JOIN template t ON t.id = tc.template_id " +
                "WHERE tc.component_id = :cid AND t.template_kind = 'COSTING'")
            .setParameter("cid", componentId)
            .getSingleResult();
        if (count != null && count.longValue() > 0) {
            throw new BusinessException(400, "该组件已被 " + count.longValue() +
                " 处核价(COSTING)模板引用，不能设为 BOM 树页签——会把这些核价模板一并改成树渲染，" +
                "破坏核价侧零回归。报价侧树页签请新建专用组件。");
        }
    }

    /**
     * C1/C3 共用: default_source.path 视图路径解析正则。
     *
     * <p>匹配两种形态：
     * <ul>
     *   <li>group 1-3: {@code $$compCode.viewName.col}（GLOBAL 跨组件视图）</li>
     *   <li>group 4-5: {@code $viewName[pred].col} 或 {@code $viewName.col}（COMPONENT 视图）</li>
     * </ul>
     */
    static final Pattern VIEW_PATH_PATTERN = Pattern.compile(
        "^\\$\\$([^.$\\[.]+)\\.([^.$\\[.]+)\\.([^.$\\[.]+)" +  // group1=compCode group2=viewName group3=col
        "|" +
        "^\\$([^.$\\[.]+)(?:\\[[^\\]]*\\])?\\.([^.$\\[.]+)$"    // group4=viewName group5=col
    );

    public static void assertValidComponentType(String type) {
        String t = type == null ? "NORMAL" : type;
        if (!VALID_COMPONENT_TYPES.contains(t)) {
            throw new BusinessException("Invalid component_type: " + t +
                ". Must be one of: " + VALID_COMPONENT_TYPES);
        }
    }

    @Inject
    EntityManager em;

    @Inject
    TemplateService templateService;

    @Inject
    ComponentSqlViewRepository sqlViewRepository;

    public List<ComponentDTO> list(UUID directoryId, String keyword) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (directoryId != null) {
            query.append(" AND directoryId = :directoryId");
            params.put("directoryId", directoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (name LIKE :kw OR code LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }
        return Component.<Component>list(query + " ORDER BY createdAt ASC", params)
            .stream()
            .map(ComponentDTO::from)
            .collect(Collectors.toList());
    }

    public ComponentDTO getById(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        return ComponentDTO.from(component);
    }

    @Transactional
    public ComponentDTO create(CreateComponentRequest request) {
        validateRequest(request);

        String fieldsJson = toJson(request.fields);

        List<Map<String, Object>> fieldList = parseList(fieldsJson);
        List<Map<String, Object>> formulaList = parseList(toJson(request.formulas));

        validateFields(fieldList);
        validateFormulas(fieldList, formulaList);  // may auto-correct formula names in-place
        detectFormulaCircularReferences(formulaList, fieldList);

        // Re-serialize after auto-correction
        String formulasJson = toJson(formulaList);

        // Auto-generate code if not provided
        String code;
        if (request.code != null && !request.code.isBlank()) {
            code = request.code.trim();
            long count = Component.count("code", code);
            if (count > 0) {
                throw new BusinessException("Component code already exists: " + code);
            }
        } else {
            Long seq = (Long) em.createNativeQuery("SELECT nextval('component_code_seq')").getSingleResult();
            code = String.format("COMP-%04d", seq);
        }

        Component component = new Component();
        component.name = request.name.trim();
        component.code = code;
        component.directoryId = request.directoryId;
        component.fields = fieldsJson;
        component.formulas = formulasJson;
        component.columnCount = fieldList.size();
        component.componentType = request.componentType != null ? request.componentType : "NORMAL";
        component.excelColumns = request.excelColumns != null ? request.excelColumns : "[]";
        component.dataDriverPath = normalizeDriverPath(request.dataDriverPath);
        component.status = request.status != null ? request.status : "ACTIVE";

        // rowKeyFields：直接透传 JSON 字符串（前端传 List，序列化为 JSON；null=未配置）
        if (request.rowKeyFields != null) {
            component.rowKeyFields = toJsonRaw(request.rowKeyFields);
        }

        // 树表配置:校验后存 JSON(null=非树表)
        validateTreeConfig(request.treeConfig, request.fields);
        component.treeConfig = request.treeConfig != null ? toJsonRaw(request.treeConfig) : null;
        // 核价 BOM 递归展开开关(默认 false:勾选才递归)
        component.bomRecursiveExpand = request.bomRecursiveExpand != null ? request.bomRecursiveExpand : Boolean.FALSE;
        // task-0721 B4：页签类型属性(校验 + COSTING 模板反向护栏 + 与 bomRecursiveExpand 自动同步；
        // 传值时覆盖上一行手动设置的 bomRecursiveExpand)。新建流程尚无 id,反向护栏天然不触发。
        // task-0721（补录）：一并写入 partNoField/partNameField + 校验"限定 tabType 必须配 partNoField"。
        applyTabType(component, request.tabType, request.partNoField, request.partNameField);
        // task-0722：行排序列(可空)。非 null 时覆盖(空串=清空)。
        if (request.sortField != null) component.sortField = request.sortField.isBlank() ? null : request.sortField;

        // 行键校验（新建路径：硬拦）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, true);

        component.persist();

        // C3: 保存后对 default_source.path 列名做软校验（只 warn，不阻断）
        warnDefaultSourcePaths(component.id, fieldList);

        LOG.infof("Created component id=%s code=%s", component.id, component.code);
        return ComponentDTO.from(component);
    }

    @Transactional
    public ComponentDTO update(UUID id, CreateComponentRequest request) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }

        if (request.code != null && !request.code.equals(component.code)) {
            long count = Component.count("code", request.code);
            if (count > 0) {
                throw new BusinessException("Component code already exists: " + request.code);
            }
            component.code = request.code.trim();
        }

        if (request.name != null && !request.name.isBlank()) {
            component.name = request.name.trim();
        }
        if (request.directoryId != null) {
            component.directoryId = request.directoryId;
        }
        if (request.componentType != null) {
            component.componentType = request.componentType;
        }
        if (request.excelColumns != null) {
            component.excelColumns = request.excelColumns;
        }
        // dataDriverPath 单独按"显式空字符串=清空"处理:null 保持不变,空串=NULL 化
        // [Y1.5 DEBUG] 记录入参,排查保存丢失
        LOG.infof("[Y1.5 component update] id=%s code=%s incoming dataDriverPath='%s' (null=%s)",
                id, component.code, request.dataDriverPath, request.dataDriverPath == null);
        if (request.dataDriverPath != null) {
            component.dataDriverPath = normalizeDriverPath(request.dataDriverPath);
            LOG.infof("[Y1.5 component update] saved dataDriverPath='%s'", component.dataDriverPath);
        }
        if (request.status != null) {
            component.status = request.status;
        }

        if (request.fields != null || request.formulas != null) {
            String fieldsJson = request.fields != null ? toJson(request.fields) : component.fields;

            List<Map<String, Object>> fieldList = parseList(fieldsJson);
            List<Map<String, Object>> formulaList = parseList(
                request.formulas != null ? toJson(request.formulas) : component.formulas
            );

            validateFields(fieldList);
            validateFormulas(fieldList, formulaList);  // may auto-correct formula names in-place
            detectFormulaCircularReferences(formulaList, fieldList);

            // Re-serialize after auto-correction
            component.fields = fieldsJson;
            component.formulas = toJson(formulaList);
            component.columnCount = fieldList.size();

            // C3: default_source.path 列名软校验（只 warn，不阻断）
            warnDefaultSourcePaths(id, fieldList);
        }

        // rowKeyFields 更新（null=不变，传值=覆盖）
        if (request.rowKeyFields != null) {
            component.rowKeyFields = toJsonRaw(request.rowKeyFields);
        }

        // 树表配置更新(null=不变;传值=覆盖,空对象/缺字段=清空)
        if (request.treeConfig != null) {
            validateTreeConfig(request.treeConfig, component.fields);
            boolean hasBoth = request.treeConfig.get("idField") != null
                    && request.treeConfig.get("parentField") != null;
            component.treeConfig = hasBoth ? toJsonRaw(request.treeConfig) : null;
        }
        // 核价 BOM 递归展开开关更新(null=不变)
        if (request.bomRecursiveExpand != null) {
            component.bomRecursiveExpand = request.bomRecursiveExpand;
        }
        // task-0721 B4：页签类型属性(校验 + COSTING 模板反向护栏 + 与 bomRecursiveExpand 自动同步；
        // 传值时覆盖上面手动设置的 bomRecursiveExpand)。
        // task-0721（补录）：一并写入 partNoField/partNameField + 校验"限定 tabType 必须配 partNoField"
        // ——校验对象是合并后的最终状态,即便本次只改 partNoField 不改 tabType 也会校验。
        applyTabType(component, request.tabType, request.partNoField, request.partNameField);
        // task-0722：行排序列(可空)。非 null 时覆盖(空串=清空)。
        if (request.sortField != null) component.sortField = request.sortField.isBlank() ? null : request.sortField;

        // 行键校验（更新路径：软校验，违规只告警不阻断）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, false);

        LOG.infof("Updated component id=%s code=%s", id, component.code);

        // H1: 自动同步引用该组件的所有模板 snapshot — 配置中心原则:
        // 组件配置是真理源, snapshot 是缓存视图. 配置变更必须对所有引用方立即生效,
        // 避免 V184/V185/V187 那种手工 DO $$ 循环刷 snapshot 的反复工作.
        try {
            List<UUID> affected = templateService.refreshSnapshotsByComponent(id);
            if (!affected.isEmpty()) {
                LOG.infof("[H1 auto-sync] componentId=%s synced %d template snapshots", id, affected.size());
            }
        } catch (Exception e) {
            // snapshot 同步失败不阻断组件保存; 仅记录警告 (用户可手工调 refresh 端点重试)
            LOG.warnf("[H1 auto-sync] failed for componentId=%s: %s", id, e.getMessage());
        }

        // Bug3 源→副本同步：EXCEL 源组件保存后，把 excelColumns(含 TAB_JOIN 列的 expression/tabs)
        // 刷到所有导入副本。副本无显式外键，靠 code 的 __impN 后缀 + 同 base 识别(与
        // ComponentImportService 同款约定)。模板引用的是副本(Bug1 下拉只列本目录副本)，
        // 故源改公式必须传导到副本，模板保存/报价渲染才能拿到最新列定义。
        try {
            int synced = syncExcelColumnsToImportedCopies(component);
            if (synced > 0) {
                LOG.infof("[Excel source-sync] source=%s synced excelColumns to %d imported copies",
                        component.code, synced);
            }
        } catch (Exception e) {
            // 同步失败不阻断源组件保存；记录警告，副本可由用户重新保存源触发重试。
            LOG.warnf("[Excel source-sync] failed for componentId=%s: %s", id, e.getMessage());
        }

        return ComponentDTO.from(component);
    }

    /**
     * 设置/清空组件的驱动视图。data_driver_path 唯一真源，值形态 $视图名。
     *
     * @param sqlViewName 本组件 ACTIVE SQL 视图名（不含 $）；null/空=清空驱动。
     */
    @Transactional
    public ComponentDTO setDriverView(UUID componentId, String sqlViewName) {
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }
        if (sqlViewName == null || sqlViewName.isBlank()) {
            component.dataDriverPath = null;
        } else {
            String name = sqlViewName.trim();
            boolean exists = sqlViewRepository
                    .findByComponentAndName(componentId, name)
                    .isPresent();
            if (!exists) {
                throw new BusinessException(400,
                        "SQL 视图不存在或未启用：" + name);
            }
            component.dataDriverPath = normalizeDriverPath("$" + name);
        }
        LOG.infof("[driver-view] componentId=%s set dataDriverPath='%s'",
                componentId, component.dataDriverPath);

        // 行键校验（软校验，违规只告警不阻断，与 update() 一致）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, false);

        // 配置中心原则：driver 变更后同步所有引用该组件的模板 snapshot
        try {
            templateService.refreshSnapshotsByComponent(componentId);
        } catch (Exception e) {
            LOG.warnf("[driver-view] snapshot refresh failed componentId=%s: %s",
                    componentId, e.getMessage());
        }
        return ComponentDTO.from(component);
    }

    /**
     * 正则：匹配 code 的 __impN 导入副本后缀（与 ComponentImportService.IMP_SUFFIX 同款）。
     * COMP-0035__imp1 → 命中(base=COMP-0035)；COMP-0035 → 不命中(它是源)。
     */
    private static final Pattern IMP_SUFFIX = Pattern.compile("^(.+?)(__imp\\d+)$");

    /** 提取 code 的 base（去掉 __impN 后缀）。COMP-0035__imp1 → COMP-0035；COMP-0035 → COMP-0035。 */
    private static String extractBase(String code) {
        if (code == null) return "";
        Matcher m = IMP_SUFFIX.matcher(code);
        return m.matches() ? m.group(1) : code;
    }

    /**
     * Bug3：把 EXCEL 源组件的 excelColumns 同步到所有导入副本（同 base code 且带 __impN 后缀）。
     *
     * <p>纪律（防 AP-40 多实例污染）：仅当被保存组件本身是<b>源</b>（code 无 __impN 后缀）时才向下传导；
     * 若被保存的是副本则跳过（不反向污染源/兄弟副本）。用 Panache 托管实体逐个赋值，由 Hibernate
     * 脏检查 flush；不走 firstResult，按 base 精确全匹配。
     *
     * @return 被同步的副本数量
     */
    private int syncExcelColumnsToImportedCopies(Component source) {
        if (source == null || !"EXCEL".equals(source.componentType)) return 0;
        // 被保存的是副本 → 不传导（源→副本单向）
        if (IMP_SUFFIX.matcher(source.code).matches()) return 0;
        String base = source.code;
        List<Component> excelComps = Component.list("componentType", "EXCEL");
        int cnt = 0;
        for (Component c : excelComps) {
            if (c.id.equals(source.id)) continue;
            if (!IMP_SUFFIX.matcher(c.code).matches()) continue;   // 只刷副本
            if (!base.equals(extractBase(c.code))) continue;        // 同 base
            if (java.util.Objects.equals(c.excelColumns, source.excelColumns)) continue; // 无变化跳过
            c.excelColumns = source.excelColumns;                   // 托管实体，脏检查自动 flush
            cnt++;
        }
        return cnt;
    }

    @Transactional
    public ComponentDTO toggleStatus(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        if ("ACTIVE".equals(component.status)) {
            component.status = "DISABLED";
            LOG.infof("Disabled component id=%s code=%s", id, component.code);
        } else {
            component.status = "ACTIVE";
            LOG.infof("Enabled component id=%s code=%s", id, component.code);
        }
        return ComponentDTO.from(component);
    }

    @Transactional
    public void delete(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        checkNotReferencedByTemplate(id);
        component.delete();
        LOG.infof("Deleted component id=%s code=%s", id, component.code);
    }

    // -----------------------------------------------------------------------
    // 行键校验（报价单整份快照 Phase 1 §5.1）
    // -----------------------------------------------------------------------

    /**
     * 校验组件的行键配置是否合法。
     *
     * <p>校验触发条件：{@code dataDriverPath} 非空（多行 driver）且 {@code fieldsJson}
     * 含至少一个可录入字段（field_type ∈ EDITABLE_FIELD_TYPES）。
     *
     * <p>豁免：
     * <ul>
     *   <li>单行/固定组件（{@code dataDriverPath} 为空）→ 直接通过</li>
     *   <li>纯只读 driver 组件（无可编辑字段）→ 直接通过</li>
     *   <li>哨兵 {@code ["__seq_no__"]} → 显式豁免（按行号对齐），直接通过</li>
     * </ul>
     *
     * @param dataDriverPath  组件 data_driver_path（BNF 路径或 $xxx_view 引用）
     * @param fieldsJson      组件 fields JSON 字符串（数组）
     * @param rowKeyFieldsJson 组件 row_key_fields JSON 字符串（数组或 null）
     * @param hard            true=新建路径，违规抛 IllegalArgumentException；
     *                        false=更新路径，违规仅 LOG.warn（不阻断存量组件保存）
     */
    public void validateRowKeyConfig(String dataDriverPath, String fieldsJson,
                                     String rowKeyFieldsJson, boolean hard) {
        // 豁免：单行/固定（无 driver）
        if (dataDriverPath == null || dataDriverPath.isBlank()) return;

        com.fasterxml.jackson.databind.JsonNode fields = readJsonNode(fieldsJson);
        boolean hasEditable = false;
        Set<String> fieldNames = new java.util.HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode f : fields) {
            String name = f.path("name").asText(null);
            if (name != null) fieldNames.add(name);
            String ft = f.path("field_type").asText(null);
            if (ft != null && EDITABLE_FIELD_TYPES.contains(ft)) hasEditable = true;
        }
        // 豁免：纯只读 driver（无可编辑字段）
        if (!hasEditable) return;

        com.fasterxml.jackson.databind.JsonNode keys = (rowKeyFieldsJson == null || rowKeyFieldsJson.isBlank())
            ? null : readJsonNode(rowKeyFieldsJson);

        // rowKeyFields 为空数组或 null → 违规
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            failRowKey(hard,
                "含可编辑字段的多行组件（dataDriverPath=" + dataDriverPath + "）必须声明 rowKeyFields");
            return;
        }
        // 哨兵 ["__seq_no__"] → 显式豁免
        if (keys.size() == 1 && "__seq_no__".equals(keys.get(0).asText())) return;

        // 方案 A: rowKeyFields 引用的是 driverRow 的底层列(运行期 expand 才有), 与 fields 中文展示名
        // 属不同命名空间 → 配置期无法对 fields 校验存在性。仅校验每个 key 为非空字符串;
        // driverRow 列名的正确性由配置者/迁移负责(详见 V279 + spec §5.1)。
        for (com.fasterxml.jackson.databind.JsonNode k : keys) {
            String keyName = k.asText(null);
            if (keyName == null || keyName.isBlank()) {
                failRowKey(hard, "rowKeyFields 含空 key（应为 driverRow 的底层列名，如 child_hf_part_no）");
                return;
            }
        }
    }

    /**
     * 树表配置软校验:开启时 idField/parentField 均必填且不同列,且两列须存在于组件字段名集合。
     * 不满足 → IllegalArgumentException(保存阻断)。传 null 或空对象(关闭树表)→ 直接通过。
     */
    public void validateTreeConfig(Map<String, Object> treeConfig, Object fieldsJsonOrList) {
        if (treeConfig == null) return;
        Object idF = treeConfig.get("idField");
        Object pF = treeConfig.get("parentField");
        boolean idEmpty = idF == null || idF.toString().isBlank();
        boolean pEmpty = pF == null || pF.toString().isBlank();
        if (idEmpty && pEmpty) return; // 视为关闭树表
        if (idEmpty || pEmpty) {
            throw new IllegalArgumentException("树表配置:ID 列与父 ID 列均必填(当前仅填了一个)");
        }
        if (idF.toString().equals(pF.toString())) {
            throw new IllegalArgumentException("树表配置:ID 列与父 ID 列不能为同一列");
        }
        java.util.Set<String> names = extractFieldNames(fieldsJsonOrList);
        if (!names.contains(idF.toString()) || !names.contains(pF.toString())) {
            throw new IllegalArgumentException("树表配置:idField/parentField 必须是组件已配置字段名");
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> extractFieldNames(Object fieldsJsonOrList) {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            java.util.List<Map<String, Object>> list;
            if (fieldsJsonOrList instanceof String s) {
                if (s.isBlank()) return names;
                list = MAPPER.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } else if (fieldsJsonOrList instanceof java.util.List<?> l) {
                list = (java.util.List<Map<String, Object>>) l;
            } else return names;
            for (Map<String, Object> f : list) {
                Object nm = f.get("name");
                if (nm != null) names.add(nm.toString());
            }
        } catch (Exception ignore) { }
        return names;
    }

    /** 违规处理：hard=true 抛，hard=false 仅告警。 */
    private void failRowKey(boolean hard, String msg) {
        if (hard) throw new IllegalArgumentException(msg);
        LOG.warnf("[rowKeyFields soft-validation] %s", msg);
    }

    /** 解析 JSON 字符串为 JsonNode；null/空/异常时返回空数组节点。 */
    private com.fasterxml.jackson.databind.JsonNode readJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json: " + e.getMessage(), e);
        }
    }

    // ---- Validation helpers ----

    private void validateRequest(CreateComponentRequest request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BusinessException("Component name is required");
        }
        // code is now auto-generated if not provided
        assertValidComponentType(request.componentType);
    }

    private void validateFields(List<Map<String, Object>> fields) {
        for (Map<String, Object> field : fields) {
            Object fieldType = field.get("field_type");
            if (fieldType == null) {
                throw new BusinessException("Each field must have a field_type");
            }
            if (!VALID_FIELD_TYPES.contains(fieldType.toString())) {
                throw new BusinessException("Invalid field_type: " + fieldType +
                    ". Must be one of: " + VALID_FIELD_TYPES);
            }
            // Plan 3a：条件公式校验 —— 默认必填 + 至少 1 条规则。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object def = cfm.get("default");
                if (def == null || String.valueOf(def).isBlank()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式缺少默认公式（default）");
                }
                Object rules = cfm.get("rules");
                if (!(rules instanceof java.util.List<?> rl) || rl.isEmpty()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式至少需 1 条规则");
                }
            }
            // DATA_SOURCE requires datasource_binding (H2: 4 种 type 各自校验关键配置)
            if ("DATA_SOURCE".equals(fieldType.toString())) {
                Object binding = field.get("datasource_binding");
                if (binding == null) {
                    throw new BusinessException("DATA_SOURCE field requires datasource_binding");
                }
                if (binding instanceof Map) {
                    Map<?, ?> b = (Map<?, ?>) binding;
                    // type 缺省 = DATABASE_QUERY (兼容 H2 前的老配置)
                    String dsType = b.get("type") != null ? b.get("type").toString() : "DATABASE_QUERY";
                    switch (dsType) {
                        case "DATABASE_QUERY":
                            if (b.get("datasource_id") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/DATABASE_QUERY 缺 datasource_id");
                            }
                            break;
                        case "GLOBAL_VARIABLE":
                            if (b.get("global_variable_code") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/GLOBAL_VARIABLE 缺 global_variable_code");
                            }
                            break;
                        case "BNF_PATH":
                            Object p = b.get("bnf_path");
                            if (p == null || p.toString().isBlank()) {
                                throw new BusinessException("DATA_SOURCE/BNF_PATH 缺 bnf_path");
                            }
                            break;
                        case "HTTP_API":
                            Object ac = b.get("api_config");
                            if (!(ac instanceof Map) || ((Map<?, ?>) ac).get("url_template") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/HTTP_API 缺 api_config.url_template");
                            }
                            break;
                        default:
                            throw new BusinessException("DATA_SOURCE 不支持的 type: " + dsType);
                    }
                }
            }
        }
        // 多小计列（Plan 2-核心）：不再限制 is_subtotal 数量，每个被标记字段各算一列总计。
    }

    /** Package-private for unit testing (cross_tab_ref structural validation). */
    void validateFormulas(List<Map<String, Object>> fields, List<Map<String, Object>> formulas) {
        // Validate formula names are not empty
        Set<String> formulaNames = new HashSet<>();
        for (Map<String, Object> formula : formulas) {
            Object formulaName = formula.get("name");
            if (formulaName == null || formulaName.toString().isBlank()) {
                throw new BusinessException("公式名称不能为空");
            }
            if (!formulaNames.add(formulaName.toString())) {
                throw new BusinessException("公式名称不能重复: " + formulaName);
            }
        }

        // Validate FORMULA fields: if formula_name is set, it must reference an existing formula
        for (Map<String, Object> field : fields) {
            if (!"FORMULA".equals(field.get("field_type"))) continue;
            Object boundName = field.get("formula_name");
            if (boundName != null && !boundName.toString().isBlank()) {
                if (!formulaNames.contains(boundName.toString())) {
                    throw new BusinessException(
                        "字段 '" + field.get("name") + "' 绑定的公式 '" + boundName + "' 不存在");
                }
            }
            // Plan 3c：条件公式引用校验 —— rules[].formula + default 必须存在。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object rules = cfm.get("rules");
                if (rules instanceof java.util.List<?> rl) {
                    for (Object r : rl) {
                        if (r instanceof Map<?, ?> rm) {
                            Object fn = rm.get("formula");
                            if (fn != null && !fn.toString().isBlank() && !formulaNames.contains(fn.toString())) {
                                throw new BusinessException("字段「" + field.get("name") + "」条件规则引用的公式 '" + fn + "' 不存在");
                            }
                        }
                    }
                }
                Object def = cfm.get("default");
                if (def != null && !def.toString().isBlank() && !formulaNames.contains(def.toString())) {
                    throw new BusinessException("字段「" + field.get("name") + "」默认公式 '" + def + "' 不存在");
                }
            }
        }
        // Plan 3c：硬环检测（含条件依赖）。转 JsonNode 复用引擎依赖图(buildFormulaDeps)。
        com.fasterxml.jackson.databind.ObjectMapper cycMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> cyclic = new com.cpq.quotation.service.FormulaCalculator()
            .cyclicFormulaNodes(cycMapper.valueToTree(fields), cycMapper.valueToTree(formulas));
        if (!cyclic.isEmpty()) {
            throw new BusinessException("公式存在循环引用: " + String.join(", ", cyclic));
        }

        // Validate cross_tab_ref tokens in formula expressions
        for (Map<String, Object> formula : formulas) {
            Object expr = formula.get("expression");
            if (!(expr instanceof List)) continue;
            for (Object operand : (List<?>) expr) {
                if (!(operand instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> token = (Map<String, Object>) operand;
                Object typeObj = token.get("type");
                if (!"cross_tab_ref".equals(typeObj)) continue;

                Object srcObj = token.get("source");
                String src = srcObj == null ? null : srcObj.toString();
                if (src == null || src.isBlank())
                    throw new BusinessException(400, "跨页签引用缺少源组件(source)");

                Object matchObj = token.get("match");
                boolean emptyMatch = !(matchObj instanceof List<?> ml) || ml.isEmpty();
                boolean hasPredicate = token.get("predicate") != null;
                if (emptyMatch && !hasPredicate)   // SUMIF 族用 predicate 过滤，match 可空
                    throw new BusinessException(400, "跨页签引用缺少匹配列(match)");

                Object aggObj = token.get("agg");
                String agg = aggObj == null ? null : aggObj.toString();
                Set<String> okAgg = Set.of("NONE", "SUM", "AVG", "COUNT", "MAX", "MIN");
                if (agg == null || !okAgg.contains(agg.toUpperCase()))
                    throw new BusinessException(400, "跨页签引用聚合方式非法: " + agg);

                Object tgtObj = token.get("target");
                String target = tgtObj == null ? null : tgtObj.toString();
                Object targetExprObj = token.get("targetExpr");
                boolean hasTargetExpr = targetExprObj instanceof java.util.List<?> tl && !tl.isEmpty();
                if (!"COUNT".equalsIgnoreCase(agg) && (target == null || target.isBlank()) && !hasTargetExpr)
                    throw new BusinessException(400, "跨页签引用缺少目标列或目标公式");

                // SUMIF 族：predicate 字段存在时，结构必须可解析（复用模型转换做结构校验）
                Object pred = token.get("predicate");
                if (pred != null) {
                    try {
                        com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                            MAPPER.valueToTree(pred));
                    } catch (Exception e) {
                        throw new BusinessException(400, "cross_tab_ref.predicate 结构非法: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Detects circular references in formula dependency graph.
     *
     * <p>Two reference patterns are tracked:
     * <ol>
     *   <li>Direct: formula A's expression references formula name B (legacy fall-back)</li>
     *   <li>Via FORMULA field binding: formula A references a FORMULA field whose
     *       {@code formula_name} attribute points to formula B → A depends on B</li>
     * </ol>
     *
     * <p>Cross-component references (type=component_subtotal) and quotation fields are
     * external leaves and skipped.
     */
    private void detectFormulaCircularReferences(List<Map<String, Object>> formulas, List<Map<String, Object>> fields) {
        // Map: FORMULA field name -> formula name that produces it (via formula_name binding,
        // or by name match as fallback). This is the missing piece that lets us track
        // dependencies when a formula references a field name (not a formula name directly).
        Set<String> formulaNames = new HashSet<>();
        for (Map<String, Object> formula : formulas) {
            Object nameObj = formula.get("name");
            if (nameObj != null) formulaNames.add(nameObj.toString());
        }

        Map<String, String> fieldNameToFormulaName = new HashMap<>();
        for (Map<String, Object> field : fields) {
            if (!"FORMULA".equals(field.get("field_type"))) continue;
            Object fName = field.get("name");
            if (fName == null) continue;
            String fieldName = fName.toString();
            Object boundFormula = field.get("formula_name");
            String formulaName = (boundFormula != null && !boundFormula.toString().isBlank())
                    ? boundFormula.toString()
                    : (formulaNames.contains(fieldName) ? fieldName : null);
            if (formulaName != null) {
                fieldNameToFormulaName.put(fieldName, formulaName);
            }
        }

        // Initialize dependency map for every formula
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (String fn : formulaNames) deps.put(fn, new HashSet<>());

        // Parse each formula's expression to extract dependencies
        for (Map<String, Object> formula : formulas) {
            Object nameObj = formula.get("name");
            if (nameObj == null) continue;
            String formulaName = nameObj.toString();
            Object expr = formula.get("expression");
            if (!(expr instanceof List)) continue;

            for (Object operand : (List<?>) expr) {
                if (!(operand instanceof Map)) continue;
                Map<?, ?> op = (Map<?, ?>) operand;
                Object type = op.get("type");
                // Skip external references and literals
                if ("component_subtotal".equals(type) || "cross_component_subtotal".equals(type)) continue;
                if ("quotation_field".equals(type)) continue;
                if ("path".equals(type) || "global_variable".equals(type)) continue;  // V104: 跨表/全局变量, 与本组件公式依赖无关
                if ("operator".equals(type) || "bracket_open".equals(type) || "bracket_close".equals(type) || "number".equals(type)) continue;

                // Try every plausible ref-bearing key. T4 tests showed callers may use any of:
                //   value / ref / formulaName / fieldName / name / formula_name
                String refName = null;
                for (String refKey : new String[] {"value", "ref", "formulaName", "fieldName", "name", "formula_name"}) {
                    Object v = op.get(refKey);
                    if (v != null && !v.toString().isBlank()) {
                        refName = v.toString();
                        break;
                    }
                }
                if (refName == null) continue;

                // Resolve dependency:
                //   - "formula_ref" type explicitly references a formula by name (T4 P1 finding)
                //   - FORMULA-field binding maps a field name to its formula
                //   - Fall back to direct formula-name match
                String depFormula = null;
                if ("formula_ref".equals(type) && formulaNames.contains(refName)) {
                    depFormula = refName;
                }
                if (depFormula == null) depFormula = fieldNameToFormulaName.get(refName);
                if (depFormula == null && formulaNames.contains(refName)) depFormula = refName;

                if (depFormula != null && !depFormula.equals(formulaName)) {
                    deps.get(formulaName).add(depFormula);
                } else if (depFormula != null && depFormula.equals(formulaName)) {
                    // Self-reference is a trivial cycle of length 1 — fail fast
                    throw new BusinessException("公式 '" + formulaName + "' 存在自引用循环");
                }
            }
        }

        // DFS cycle detection
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String name : deps.keySet()) {
            if (!visited.contains(name)) {
                if (dfsCycleDetect(name, deps, visited, inStack)) {
                    throw new BusinessException("公式存在循环引用，请检查公式间的依赖关系");
                }
            }
        }
    }

    private boolean dfsCycleDetect(String node, Map<String, Set<String>> deps,
                                    Set<String> visited, Set<String> inStack) {
        visited.add(node);
        inStack.add(node);
        Set<String> neighbors = deps.getOrDefault(node, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                if (dfsCycleDetect(neighbor, deps, visited, inStack)) return true;
            } else if (inStack.contains(neighbor)) {
                return true;
            }
        }
        inStack.remove(node);
        return false;
    }

    private void checkNotReferencedByTemplate(UUID componentId) {
        try {
            Long tableExists = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'template_component'"
            ).getSingleResult();
            if (tableExists == null || tableExists == 0) {
                return;
            }
            Long refCount = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM template_component WHERE component_id = :cid"
            ).setParameter("cid", componentId).getSingleResult();
            if (refCount != null && refCount > 0) {
                throw new BusinessException("Cannot delete component that is referenced by a template");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugf("Template reference check skipped for componentId=%s: %s", componentId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // C1: 全库 BASIC_DATA path↔视图列名审计（只读，不修改数据）
    // -----------------------------------------------------------------------

    /**
     * 审计全库所有组件字段的 {@code default_source.path}，检出 path 末段列名与
     * 该组件 {@code component_sql_view.declared_columns} 不一致的可疑项。
     *
     * <p>只处理形如 {@code $viewName.col} 或 {@code $$compCode.viewName.col} 的路径；
     * 其他形式（无 $ 前缀的 BNF 路径）直接跳过。
     *
     * <p>每个可疑项包含以下字段：
     * <ul>
     *   <li>{@code componentId}     — 组件 UUID</li>
     *   <li>{@code componentCode}   — 组件业务 code</li>
     *   <li>{@code fieldName}       — 字段名（fields[].name）</li>
     *   <li>{@code path}            — 原始 default_source.path</li>
     *   <li>{@code viewName}        — 提取的视图名</li>
     *   <li>{@code columnName}      — 提取的末段列名</li>
     *   <li>{@code issueType}       — "columnMismatch" | "viewNotFound"</li>
     *   <li>{@code actualColumns}   — 视图实际 declared_columns 名称列表（viewNotFound 时为空）</li>
     *   <li>{@code suggestion}      — 可能的修正建议（无等价列时为 null）</li>
     * </ul>
     *
     * @return 可疑项列表（正常项不包含在内）；全部正常时返回空列表
     */
    @Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
    public List<Map<String, Object>> auditBasicDataPaths() {
        // VIEW_PATH_PATTERN 已提升为 class-level static 常量（C1/C3 共用）

        List<Map<String, Object>> suspects = new ArrayList<>();

        List<Component> allComponents = Component.<Component>listAll();

        for (Component comp : allComponents) {
            if (comp.fields == null || comp.fields.isBlank() || "[]".equals(comp.fields.trim())) {
                continue;
            }

            List<Map<String, Object>> fields;
            try {
                fields = MAPPER.readValue(comp.fields, new TypeReference<>() {});
            } catch (Exception e) {
                LOG.warnf("[auditBasicDataPaths] componentId=%s fields JSON 解析失败: %s", comp.id, e.getMessage());
                continue;
            }

            // 按视图名缓存该组件的 declared_columns（避免同视图多字段重复查 DB）
            Map<String, List<String>> viewColumnsCache = new HashMap<>();

            for (Map<String, Object> field : fields) {
                String fieldName = String.valueOf(field.getOrDefault("name", ""));
                Object defaultSource = field.get("default_source");
                if (!(defaultSource instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> ds = (Map<String, Object>) defaultSource;
                Object pathObj = ds.get("path");
                if (pathObj == null) continue;
                String path = pathObj.toString().trim();
                if (path.isEmpty()) continue;

                // 只处理 $ 开头的视图路径
                if (!path.startsWith("$")) continue;

                Matcher m = VIEW_PATH_PATTERN.matcher(path);
                if (!m.matches()) continue;

                // 解析视图名和列名
                String viewName;
                String colName;
                if (m.group(1) != null) {
                    // $$compCode.viewName.col 形态（GLOBAL）
                    // group1=compCode, group2=viewName, group3=col
                    viewName = m.group(2);
                    colName = m.group(3);
                } else {
                    // $viewName.col 形态（COMPONENT）
                    // group4=viewName, group5=col
                    viewName = m.group(4);
                    colName = m.group(5);
                }

                if (viewName == null || colName == null) continue;

                // 取该组件该视图的 declared_columns（带缓存）
                final String finalViewName = viewName;
                List<String> actualColumns = viewColumnsCache.computeIfAbsent(viewName, vn -> {
                    Optional<ComponentSqlView> csv = sqlViewRepository.findByComponentAndName(comp.id, finalViewName);
                    if (csv.isEmpty()) return null; // null 表示视图不存在
                    return extractDeclaredColumnNames(csv.get().declaredColumns);
                });

                Map<String, Object> suspect = new LinkedHashMap<>();
                suspect.put("componentId", comp.id.toString());
                suspect.put("componentCode", comp.code);
                suspect.put("fieldName", fieldName);
                suspect.put("path", path);
                suspect.put("viewName", viewName);
                suspect.put("columnName", colName);

                if (actualColumns == null) {
                    // 视图在 component_sql_view 中不存在
                    suspect.put("issueType", "viewNotFound");
                    suspect.put("actualColumns", Collections.emptyList());
                    suspect.put("suggestion", null);
                    suspects.add(suspect);
                } else if (!actualColumns.contains(colName)) {
                    // 列名与视图列不匹配 — 检查是否有下划线差异等价列
                    suspect.put("issueType", "columnMismatch");
                    suspect.put("actualColumns", actualColumns);
                    suspect.put("suggestion", buildSuggestion(colName, actualColumns, path, viewName));
                    suspects.add(suspect);
                }
                // 精确匹配 → 正常，不加入 suspects
            }
        }

        LOG.infof("[auditBasicDataPaths] 扫描完成，共检出 %d 个可疑项（组件总数=%d）",
            suspects.size(), allComponents.size());
        return suspects;
    }

    // C3: 组件保存时对 default_source.path 列名软校验
    // -----------------------------------------------------------------------

    /**
     * 对字段列表中每个 {@code default_source.path} 的末段列名做软校验：
     * 若列名不在该组件对应视图的 {@code declared_columns} 中，则产生告警（LOG.warnf）
     * 并将告警信息加入返回列表。
     *
     * <p><strong>不阻断保存</strong>：此方法永远不抛异常，存量错误配置可正常保存。
     *
     * <p>仅处理 {@code $viewName.col} / {@code $$compCode.viewName.col} 形态的 path；
     * 其他形态（无 $ 前缀的 BNF 路径）直接跳过。
     *
     * <p>Package-private 供单元测试直接调用断言 warnings。
     *
     * @param componentId 当前组件 UUID（用于查询 component_sql_view）
     * @param fields      反序列化后的字段列表
     * @return warning 字符串列表；无问题时为空列表
     */
    List<String> warnDefaultSourcePaths(UUID componentId, List<Map<String, Object>> fields) {
        if (fields == null || fields.isEmpty() || componentId == null) {
            return Collections.emptyList();
        }

        List<String> warnings = new ArrayList<>();
        // 按视图名缓存该组件的 declared_columns（避免同视图多字段重复查 DB）
        Map<String, List<String>> viewColumnsCache = new HashMap<>();

        for (Map<String, Object> field : fields) {
            // per-field 兜底：单字段校验失败只跳过该字段，绝不影响其余字段及保存事务
            String fieldNameForCatch = String.valueOf(field.getOrDefault("name", ""));
            try {
                Object defaultSource = field.get("default_source");
                if (!(defaultSource instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> ds = (Map<String, Object>) defaultSource;
                Object pathObj = ds.get("path");
                if (pathObj == null) continue;
                String path = pathObj.toString().trim();
                if (path.isEmpty() || !path.startsWith("$")) continue;

                Matcher m = VIEW_PATH_PATTERN.matcher(path);
                if (!m.matches()) continue;

                String viewName;
                String colName;
                if (m.group(1) != null) {
                    // $$compCode.viewName.col 形态
                    viewName = m.group(2);
                    colName  = m.group(3);
                } else {
                    // $viewName.col 形态
                    viewName = m.group(4);
                    colName  = m.group(5);
                }
                if (viewName == null || colName == null) continue;

                String fieldName = String.valueOf(field.getOrDefault("name", ""));
                final String fv = viewName;

                // 取该组件该视图的 declared_columns（带缓存；null 表示视图不存在）
                // computeIfAbsent lambda 内的 DB 查询异常由外层 per-field catch 捕获
                List<String> actualColumns = viewColumnsCache.computeIfAbsent(viewName, vn -> {
                    Optional<ComponentSqlView> csv = sqlViewRepository.findByComponentAndName(componentId, fv);
                    if (csv.isEmpty()) return null;
                    return extractDeclaredColumnNames(csv.get().declaredColumns);
                });

                if (actualColumns == null) {
                    String warn = String.format(
                        "[C3 default_source.path soft-warn] componentId=%s field='%s' path='%s' — 视图 '%s' 未在 component_sql_view 中找到",
                        componentId, fieldName, path, viewName);
                    LOG.warnf("%s", warn);
                    warnings.add(warn);
                } else if (!actualColumns.contains(colName)) {
                    String warn = String.format(
                        "[C3 default_source.path soft-warn] componentId=%s field='%s' path='%s' — 列名 '%s' 不在视图 '%s' 的 declared_columns %s 中",
                        componentId, fieldName, path, colName, viewName, actualColumns);
                    LOG.warnf("%s", warn);
                    warnings.add(warn);
                }
            } catch (Exception e) {
                // 软校验异常（含 DB RuntimeException / PersistenceException）：只告警，绝不逃逸
                LOG.warnf("[default_source.path soft-warn] 校验字段 '%s' 异常(已忽略,不阻断保存): %s",
                        fieldNameForCatch, e.getMessage());
            }
        }

        return warnings;
    }

    /**
     * 从 declared_columns JSON 字符串中提取列名列表。
     * 格式：[{"name":"col","dataType":"text",...}, ...]
     */
    private List<String> extractDeclaredColumnNames(String declaredColumnsJson) {
        if (declaredColumnsJson == null || declaredColumnsJson.isBlank()) return Collections.emptyList();
        try {
            JsonNode arr = MAPPER.readTree(declaredColumnsJson);
            List<String> names = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    JsonNode nameNode = node.get("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        names.add(nameNode.asText());
                    }
                }
            }
            return names;
        } catch (Exception e) {
            LOG.warnf("[declaredColumns parse] declaredColumns JSON 解析失败: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据列名与实际列列表构建修正建议。
     *
     * <p>策略：
     * <ol>
     *   <li>若 col 以 "_" 开头且去掉后命中 actualColumns → 建议去掉下划线</li>
     *   <li>若 col 不以 "_" 开头但加上 "_" 后命中 actualColumns → 建议加下划线</li>
     *   <li>否则无明显等价列 → 返回 null</li>
     * </ol>
     *
     * @return 建议字符串，如 "建议将列名「_类型」改为「类型」"；无等价列时为 null
     */
    private String buildSuggestion(String col, List<String> actualColumns, String path, String viewName) {
        if (col.startsWith("_")) {
            String withoutUnderscore = col.substring(1);
            if (actualColumns.contains(withoutUnderscore)) {
                return String.format("建议将列名「%s」改为「%s」（去掉下划线前缀），即路径改为 $%s.%s",
                    col, withoutUnderscore, viewName, withoutUnderscore);
            }
        } else {
            String withUnderscore = "_" + col;
            if (actualColumns.contains(withUnderscore)) {
                return String.format("建议将列名「%s」改为「%s」（加下划线前缀），即路径改为 $%s.%s",
                    col, withUnderscore, viewName, withUnderscore);
            }
        }
        return null;
    }

    /** 规范化 driver path:剥花括号 + trim,空字符串 → null。 */
    private String normalizeDriverPath(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("{") && t.endsWith("}")) {
            t = t.substring(1, t.length() - 1).trim();
            if (t.isEmpty()) return null;
        }
        return t;
    }

    private String toJson(List<Map<String, Object>> list) {
        if (list == null) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 序列化任意 List 为 JSON（用于 rowKeyFields 等简单 List<String>）。 */
    private String toJsonRaw(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
