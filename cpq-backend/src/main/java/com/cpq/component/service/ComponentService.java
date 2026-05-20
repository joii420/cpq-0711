package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.entity.Component;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComponentService {

    private static final Logger LOG = Logger.getLogger(ComponentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> VALID_FIELD_TYPES = Set.of(
        "FIXED_VALUE", "DATA_SOURCE", "INPUT", "INPUT_TEXT", "INPUT_NUMBER", "FORMULA",
        "BASIC_DATA",  // V5: BNF 路径绑定基础数据物理表(对应前端 PathPickerDrawer)
        "LIST_FORMULA" // V203/Phase B: 配置模板驱动 + IF-ELSE-IF 条件分支公式
    );

    @Inject
    EntityManager em;

    @Inject
    TemplateService templateService;

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
        component.dataDriverPath = normalizeDriverPath(request.dataDriverPath);
        component.status = request.status != null ? request.status : "ACTIVE";
        component.persist();

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
        }

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

        return ComponentDTO.from(component);
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

    // ---- Validation helpers ----

    private void validateRequest(CreateComponentRequest request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BusinessException("Component name is required");
        }
        // code is now auto-generated if not provided
    }

    private void validateFields(List<Map<String, Object>> fields) {
        int subtotalCount = 0;
        for (Map<String, Object> field : fields) {
            Object fieldType = field.get("field_type");
            if (fieldType == null) {
                throw new BusinessException("Each field must have a field_type");
            }
            if (!VALID_FIELD_TYPES.contains(fieldType.toString())) {
                throw new BusinessException("Invalid field_type: " + fieldType +
                    ". Must be one of: " + VALID_FIELD_TYPES);
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
            // Count is_subtotal
            Object isSubtotal = field.get("is_subtotal");
            if (Boolean.TRUE.equals(isSubtotal) || "true".equals(String.valueOf(isSubtotal))) {
                subtotalCount++;
            }
        }
        if (subtotalCount > 1) {
            throw new BusinessException("At most one field can have is_subtotal=true");
        }
    }

    private void validateFormulas(List<Map<String, Object>> fields, List<Map<String, Object>> formulas) {
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

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
