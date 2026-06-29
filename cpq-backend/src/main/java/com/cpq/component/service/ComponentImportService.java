package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.dto.ImportPreviewResult;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentDirectory;
import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 组件目录 **导入预览(P2,dry-run)** 服务。
 *
 * <p>只读校验 + 生成计划,**绝不写库**:依赖存在性校验 + code 冲突计划 + checksum 校验。
 * 提交(P3)单独实现。设计见 docs/PRD-v3.md §5.4.6。
 */
@ApplicationScoped
public class ComponentImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = Logger.getLogger(ComponentImportService.class);

    @Inject
    EntityManager em;

    @Transactional(Transactional.TxType.SUPPORTS)
    public ImportPreviewResult preview(UUID targetDirId, ComponentExportBundle bundle, String conflictPolicy) {
        ComponentDirectory dir = ComponentDirectory.findById(targetDirId);
        if (dir == null) {
            throw new BusinessException(404, "目标目录不存在: " + targetDirId);
        }
        if (bundle == null || bundle.components == null) {
            throw new BusinessException(400, "bundle 为空或格式不正确(缺 components)");
        }
        String policy = (conflictPolicy == null || conflictPolicy.isBlank())
                ? "RENAME" : conflictPolicy.trim().toUpperCase();
        if (!policy.equals("RENAME") && !policy.equals("SKIP") && !policy.equals("ABORT")) {
            throw new BusinessException(400, "conflictPolicy 仅支持 RENAME / SKIP / ABORT");
        }

        ImportPreviewResult r = new ImportPreviewResult();
        r.bundleVersion = bundle.bundleVersion;
        r.targetDirectoryId = targetDirId.toString();
        r.targetDirectoryName = dir.name;
        r.conflictPolicy = policy;
        r.checksumValid = verifyChecksum(bundle);
        r.blockers = new ArrayList<>();

        // ── 依赖校验 ──────────────────────────────────────────────
        ImportPreviewResult.DependencyCheck dep = new ImportPreviewResult.DependencyCheck();
        dep.globalVariables = new ArrayList<>();
        dep.datasources = new ArrayList<>();
        int missing = 0;
        if (bundle.dependencies != null) {
            if (bundle.dependencies.globalVariables != null) {
                for (String code : bundle.dependencies.globalVariables) {
                    boolean exists = countNative(
                            "SELECT count(*) FROM global_variable_definition WHERE code = :c", code) > 0;
                    dep.globalVariables.add(depItem(code, exists));
                    if (!exists) missing++;
                }
            }
            if (bundle.dependencies.datasources != null) {
                for (String code : bundle.dependencies.datasources) {
                    boolean exists = countNative(
                            "SELECT count(*) FROM datasource WHERE code = :c", code) > 0;
                    dep.datasources.add(depItem(code, exists));
                    if (!exists) missing++;
                }
            }
        }
        dep.missingCount = missing;
        r.dependencies = dep;

        // ── code 冲突计划 ─────────────────────────────────────────
        // 收集 bundle 内所有 code(用于重命名时避开同批次将创建的 code)
        Set<String> bundleCodes = new LinkedHashSet<>();
        for (ComponentExportBundle.Item it : bundle.components) {
            if (it.code != null) bundleCodes.add(it.code);
        }
        Set<String> existing = queryExistingCodes(bundleCodes);
        // reserved = 现有 DB code ∪ bundle 内所有 code ∪ 已分配的新 code
        Set<String> reserved = new HashSet<>(existing);
        reserved.addAll(bundleCodes);

        List<ImportPreviewResult.ComponentPlan> plans = new ArrayList<>();
        int create = 0, rename = 0, skip = 0, conflicts = 0;
        for (ComponentExportBundle.Item it : bundle.components) {
            ImportPreviewResult.ComponentPlan p = new ImportPreviewResult.ComponentPlan();
            p.code = it.code;
            p.name = it.name;
            p.sqlViewCount = it.sqlViews == null ? 0 : it.sqlViews.size();
            boolean conflict = it.code != null && existing.contains(it.code);
            p.conflict = conflict;
            if (!conflict) {
                p.action = "CREATE";
                create++;
            } else {
                conflicts++;
                switch (policy) {
                    case "SKIP" -> { p.action = "SKIP"; skip++; }
                    case "ABORT" -> { p.action = "ABORT"; }
                    default -> { // RENAME
                        String nc = nextFreeCode(it.code, reserved);
                        reserved.add(nc);
                        p.newCode = nc;
                        p.action = "RENAME";
                        rename++;
                    }
                }
            }
            plans.add(p);
        }
        r.components = plans;

        ImportPreviewResult.Summary s = new ImportPreviewResult.Summary();
        s.total = plans.size();
        s.toCreate = create;
        s.toRename = rename;
        s.toSkip = skip;
        s.conflicts = conflicts;
        r.summary = s;

        // ── 是否可提交 ────────────────────────────────────────────
        boolean canCommit = true;
        if (missing > 0) {
            canCommit = false;
            r.blockers.add("缺失 " + missing + " 个依赖(数据源/全局变量),默认阻止提交(可在提交时显式忽略)");
        }
        if (policy.equals("ABORT") && conflicts > 0) {
            canCommit = false;
            r.blockers.add("存在 " + conflicts + " 个 code 冲突,ABORT 策略下整体中止");
        }
        if (!r.checksumValid) {
            // checksum 不一致仅警告,不强制阻止(允许手工编辑后的 bundle)
            r.blockers.add("⚠ checksum 校验不一致(bundle 可能被改动或损坏),请确认来源");
        }
        r.canCommit = canCommit;
        return r;
    }

    /**
     * 提交导入(P3):单事务,只 INSERT 新组件 + 其 component_sql_view(全新 UUID),
     * 不 UPDATE/DELETE 任何现有数据,不绑定模板。
     *
     * @param ignoreMissingDeps true 时即使依赖缺失也继续(相关字段运行时取数可能失败)
     */
    @Transactional
    public ImportCommitResult commit(UUID targetDirId, ComponentExportBundle bundle,
                                     String conflictPolicy, boolean ignoreMissingDeps) {
        ComponentDirectory dir = ComponentDirectory.findById(targetDirId);
        if (dir == null) {
            throw new BusinessException(404, "目标目录不存在: " + targetDirId);
        }
        if (bundle == null || bundle.components == null) {
            throw new BusinessException(400, "bundle 为空或格式不正确(缺 components)");
        }
        String policy = (conflictPolicy == null || conflictPolicy.isBlank())
                ? "RENAME" : conflictPolicy.trim().toUpperCase();
        if (!policy.equals("RENAME") && !policy.equals("SKIP") && !policy.equals("ABORT")) {
            throw new BusinessException(400, "conflictPolicy 仅支持 RENAME / SKIP / ABORT");
        }

        // 服务端重新校验(不信任前端): 依赖 + 冲突
        int missing = countMissingDeps(bundle);
        if (missing > 0 && !ignoreMissingDeps) {
            throw new BusinessException(400, "缺失 " + missing + " 个依赖(数据源/全局变量),已阻止提交;如确需导入请显式忽略依赖");
        }

        Set<String> bundleCodes = new LinkedHashSet<>();
        for (ComponentExportBundle.Item it : bundle.components) {
            if (it.code != null) bundleCodes.add(it.code);
        }
        Set<String> existing = queryExistingCodes(bundleCodes);
        long conflicts = bundle.components.stream()
                .filter(it -> it.code != null && existing.contains(it.code)).count();
        if (policy.equals("ABORT") && conflicts > 0) {
            throw new BusinessException(409, "存在 " + conflicts + " 个 code 冲突,ABORT 策略下整体中止");
        }

        Set<String> reserved = new HashSet<>(existing);
        reserved.addAll(bundleCodes);

        ImportCommitResult result = new ImportCommitResult();
        result.targetDirectoryId = targetDirId.toString();
        result.targetDirectoryName = dir.name;
        result.conflictPolicy = policy;
        result.created = new ArrayList<>();
        result.skipped = new ArrayList<>();
        int sqlViews = 0;

        // ── 第一遍：创建所有新组件，同时收集 idMap / codeMap ─────────────────
        // idMap:  原组件 id（bundle Item.id）→ 新副本 id（新建后的 UUID 字符串）
        // codeMap: 原组件 code（bundle Item.code）→ 新副本 finalCode
        // 仅 CREATE 的组件进 map；SKIP 的组件不进 map（引用无法重映射）
        Map<String, String> idMap = new HashMap<>();
        Map<String, String> codeMap = new HashMap<>();
        // 记录新建的组件实体，供第二遍重写 formulas
        List<Component> createdComponents = new ArrayList<>();

        boolean hasNullId = false;

        for (ComponentExportBundle.Item it : bundle.components) {
            boolean conflict = it.code != null && existing.contains(it.code);
            if (conflict && policy.equals("SKIP")) {
                result.skipped.add(it.code);
                continue;
            }
            String finalCode = it.code;
            boolean renamed = false;
            if (conflict && policy.equals("RENAME")) {
                finalCode = nextFreeCode(it.code, reserved);
                reserved.add(finalCode);
                renamed = true;
            }

            // INSERT 新组件(全新 UUID, 落到目标目录, 不绑定任何模板)
            Component c = new Component();
            c.code = finalCode;
            c.name = it.name;
            c.componentType = it.componentType == null ? "NORMAL" : it.componentType;
            c.columnCount = it.columnCount == null ? 0 : it.columnCount;
            c.status = it.status == null ? "ACTIVE" : it.status;
            c.dataDriverPath = it.dataDriverPath;
            c.fields = nodeToJson(it.fields);
            c.formulas = nodeToJson(it.formulas);
            // excel_columns 列 NOT NULL；nodeToJson 已把 null/缺失 → "[]"
            c.excelColumns = nodeToJson(it.excelColumns);
            c.directoryId = targetDirId;
            c.persist();

            // 收集 idMap：it.id 为 null 时跳过（老 bundle，UUID 类引用无法重映射）
            if (it.id != null && !it.id.isBlank()) {
                idMap.put(it.id, c.id.toString());
            } else {
                hasNullId = true;
            }
            // 收集 codeMap：原 code → finalCode（RENAME 场景 finalCode 与原 code 不同）
            if (it.code != null) {
                codeMap.put(it.code, finalCode);
            }

            createdComponents.add(c);

            int viewCnt = 0;
            if (it.sqlViews != null) {
                for (ComponentExportBundle.SqlView sv : it.sqlViews) {
                    ComponentSqlView v = new ComponentSqlView();
                    v.componentId = c.id;
                    v.sqlViewName = sv.sqlViewName;
                    v.sqlTemplate = sv.sqlTemplate;
                    v.declaredColumns = nodeToJson(sv.declaredColumns);
                    v.requiredVariables = (sv.requiredVariables == null)
                            ? new String[0] : sv.requiredVariables.toArray(new String[0]);
                    v.scope = sv.scope == null ? "COMPONENT" : sv.scope;
                    v.status = "ACTIVE";
                    v.description = sv.description;
                    v.persist();
                    viewCnt++;
                }
            }
            sqlViews += viewCnt;

            ImportCommitResult.CreatedItem ci = new ImportCommitResult.CreatedItem();
            ci.originalCode = it.code;
            ci.finalCode = finalCode;
            ci.componentId = c.id.toString();
            ci.renamed = renamed;
            ci.sqlViewCount = viewCnt;
            result.created.add(ci);
        }

        // 向后兼容提示：老 bundle 无 id 字段，UUID 类跨组件引用无法重映射
        if (hasNullId) {
            LOG.warnf("导入 bundle 含老格式 Item(id=null)，UUID 类跨组件引用(cross_tab_ref.source)未重映射；" +
                      "component_subtotal.component_code 仍通过 codeMap 映射");
        }

        // ── 第二遍：对每个新建组件重写 formulas 里的跨组件引用 ──────────────
        // 必须在第一遍全部建完（idMap/codeMap 收集完整）之后执行，
        // 因为组件 A 可能引用同批次组件 B，B 必须先进 map。
        if (!idMap.isEmpty() || !codeMap.isEmpty()) {
            for (Component c : createdComponents) {
                String remapped = FormulaRefRemapper.remap(c.formulas, idMap, codeMap);
                if (remapped != null && !remapped.equals(c.formulas)) {
                    c.formulas = remapped;
                    // Panache 实体在 @Transactional 方法内，赋值后由 Hibernate 脏检查
                    // 自动 flush；无需显式调用 c.persist()（已 managed 状态）
                }
            }
        }

        result.createdCount = result.created.size();
        result.skippedCount = result.skipped.size();
        result.sqlViewsCreated = sqlViews;
        return result;
    }

    /** 统计 bundle 依赖中在目标环境缺失的数量。 */
    private int countMissingDeps(ComponentExportBundle bundle) {
        int missing = 0;
        if (bundle.dependencies != null) {
            if (bundle.dependencies.globalVariables != null) {
                for (String code : bundle.dependencies.globalVariables) {
                    if (countNative("SELECT count(*) FROM global_variable_definition WHERE code = :c", code) == 0) missing++;
                }
            }
            if (bundle.dependencies.datasources != null) {
                for (String code : bundle.dependencies.datasources) {
                    if (countNative("SELECT count(*) FROM datasource WHERE code = :c", code) == 0) missing++;
                }
            }
        }
        return missing;
    }

    /** JsonNode → JSONB 字符串(null/缺失 → "[]")。 */
    private String nodeToJson(JsonNode node) {
        if (node == null || node.isNull()) return "[]";
        return node.toString();
    }

    /**
     * 找下一个不冲突的新 code:base__imp1 / __imp2 ...
     * 必须同时避开:① reserved(本批次已占用/bundle 自身 code) ② **数据库现有 code**
     * (含此前导入产生的 __impN —— 否则会与库内已存在的重命名 code 撞 component_code_key)。
     */
    private String nextFreeCode(String base, Set<String> reserved) {
        for (int i = 1; i < 10000; i++) {
            String candidate = base + "__imp" + i;
            if (reserved.contains(candidate)) continue;
            if (countNative("SELECT count(*) FROM component WHERE code = :c", candidate) == 0) {
                return candidate;
            }
        }
        return base + "__imp" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("unchecked")
    private Set<String> queryExistingCodes(Set<String> codes) {
        if (codes.isEmpty()) return new HashSet<>();
        List<String> rows = em.createNativeQuery(
                "SELECT code FROM component WHERE code IN :codes")
                .setParameter("codes", codes)
                .getResultList();
        return new HashSet<>(rows);
    }

    private long countNative(String sql, String code) {
        Number n = (Number) em.createNativeQuery(sql).setParameter("c", code).getSingleResult();
        return n == null ? 0 : n.longValue();
    }

    private ImportPreviewResult.DepItem depItem(String code, boolean exists) {
        ImportPreviewResult.DepItem d = new ImportPreviewResult.DepItem();
        d.code = code;
        d.exists = exists;
        return d;
    }

    /** 重算 source+components+dependencies 的 sha256 与 bundle.checksum 比对。 */
    private boolean verifyChecksum(ComponentExportBundle bundle) {
        if (bundle.checksum == null || bundle.checksum.isBlank()) return false;
        try {
            var payload = MAPPER.createObjectNode();
            payload.set("source", MAPPER.valueToTree(bundle.source));
            payload.set("components", MAPPER.valueToTree(bundle.components));
            payload.set("dependencies", MAPPER.valueToTree(bundle.dependencies));
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equals(bundle.checksum);
        } catch (Exception e) {
            return false;
        }
    }

    // ── G4: 目录级存量引用补救 ───────────────────────────────────────────────

    /**
     * 正则：匹配 code 里的 __impN 后缀，提取 base 部分。
     * 例：COMP-0031__imp1 → base = COMP-0031；COMP-0031 → base = COMP-0031（无后缀）
     */
    private static final Pattern IMP_SUFFIX = Pattern.compile("^(.+?)(__imp\\d+)$");

    /**
     * G4 目录级存量引用补救。
     *
     * <p>扫描目标目录内所有组件的 formulas，找出仍指向 **目录外** 组件的跨组件引用，
     * 并尝试将其重映射到同目录内对应的副本（base code 一致）。
     *
     * <p>映射规则：
     * <ul>
     *   <li>cross_tab_ref.source / targetExpr[].source（UUID）：若该组件不在本目录
     *       → 取其 code 去掉 __impN 后缀得 base → 在目录内按 base 找副本（code 升序取第一个）
     *       → 建 idMap</li>
     *   <li>component_subtotal.component_code（code 字符串）：若该 code 不是本目录任何组件的 code
     *       → 取 base → 找副本 → 建 codeMap</li>
     * </ul>
     *
     * @param dirId  目标目录 UUID
     * @param dryRun true = 只计算清单不写库；false = 实际更新 formulas
     * @return 每个组件的重映射/unresolved 清单汇总
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DirRemapResult remapImportedRefsInDirectory(UUID dirId, boolean dryRun) {
        ComponentDirectory dir = ComponentDirectory.findById(dirId);
        if (dir == null) {
            throw new BusinessException(404, "目录不存在: " + dirId);
        }

        // 1. 取该目录所有组件
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, code FROM component WHERE directory_id = :dir ORDER BY code")
                .setParameter("dir", dirId)
                .getResultList();

        // dirIdSet：目录内组件 id 集合（快速判断 UUID 是否在目录内）
        Set<String> dirIdSet = new HashSet<>();
        // dirCodeSet：目录内组件 code 集合（判断 code 是否在目录内）
        Set<String> dirCodeSet = new HashSet<>();
        // baseCode → 目录内副本（code 升序取第一个；List 用于多副本场景）
        // 结构：baseCode → (dirCompId, dirCompCode)，已在 ORDER BY code 顺序里取第一个
        Map<String, String[]> baseToFirstCopy = new LinkedHashMap<>(); // value = {id, code}

        for (Object[] r : rows) {
            String cid  = r[0].toString();
            String code = r[1].toString();
            dirIdSet.add(cid);
            dirCodeSet.add(code);
            // 提取 base
            String base = extractBase(code);
            // 按 code 升序，只保留同 base 的第一个
            baseToFirstCopy.putIfAbsent(base, new String[]{cid, code});
        }

        DirRemapResult result = new DirRemapResult();
        result.directoryId = dirId.toString();
        result.dryRun = dryRun;
        result.components = new ArrayList<>();

        // 2. 对每个目录组件，扫描 formulas，构造专属 idMap/codeMap
        for (Object[] r : rows) {
            UUID cid   = UUID.fromString(r[0].toString());
            String code = r[1].toString();

            DirRemapResult.ComponentResult cr = new DirRemapResult.ComponentResult();
            cr.code = code;
            cr.remapped = new ArrayList<>();
            cr.unresolved = new ArrayList<>();

            String formulasJson;
            try {
                Object raw = em.createNativeQuery(
                        "SELECT formulas::text FROM component WHERE id = :id")
                        .setParameter("id", cid)
                        .getSingleResult();
                formulasJson = raw == null ? "[]" : raw.toString();
            } catch (Exception e) {
                LOG.warnf("G4 remap: 读取组件 %s formulas 失败: %s", code, e.getMessage());
                result.components.add(cr);
                continue;
            }

            // 解析 formulas，提取跨组件引用
            JsonNode formulasNode;
            try {
                formulasNode = MAPPER.readTree(formulasJson);
            } catch (Exception e) {
                LOG.warnf("G4 remap: 解析组件 %s formulas JSON 失败: %s", code, e.getMessage());
                result.components.add(cr);
                continue;
            }

            // 收集所有 UUID 引用和 code 引用
            Set<String> uuidRefs  = extractAllUuidRefs(formulasNode);
            Set<String> codeRefs  = extractAllCodeRefs(formulasNode);

            Map<String, String> idMap   = new HashMap<>();
            Map<String, String> codeMap = new HashMap<>();

            // 处理 UUID 引用
            for (String refUuid : uuidRefs) {
                if (dirIdSet.contains(refUuid)) {
                    // 已指向目录内 → 正确，跳过
                    continue;
                }
                // 目录外：查该 UUID 对应组件的 code
                String refCode = queryComponentCode(refUuid);
                if (refCode == null) {
                    // 全库找不到该 UUID，记录 unresolved
                    cr.unresolved.add("UUID:" + refUuid + " (组件不存在)");
                    continue;
                }
                String base = extractBase(refCode);
                String[] copy = baseToFirstCopy.get(base);
                if (copy == null) {
                    // 目录内无对应副本
                    cr.unresolved.add("UUID:" + refUuid + " (base=" + base + ", 目录内无副本)");
                } else {
                    idMap.put(refUuid, copy[0]);
                    cr.remapped.add("UUID:" + refUuid + " → " + copy[0] + " (code:" + copy[1] + ")");
                }
            }

            // 处理 code 引用
            for (String refCode : codeRefs) {
                if (dirCodeSet.contains(refCode)) {
                    // 已指向目录内 code → 正确，跳过
                    continue;
                }
                String base = extractBase(refCode);
                String[] copy = baseToFirstCopy.get(base);
                if (copy == null) {
                    cr.unresolved.add("CODE:" + refCode + " (base=" + base + ", 目录内无副本)");
                } else {
                    codeMap.put(refCode, copy[1]);
                    cr.remapped.add("CODE:" + refCode + " → " + copy[1]);
                }
            }

            // 执行重映射
            if (!idMap.isEmpty() || !codeMap.isEmpty()) {
                String remapped = FormulaRefRemapper.remap(formulasJson, idMap, codeMap);
                boolean changed = remapped != null && !remapped.equals(formulasJson);
                if (changed && !dryRun) {
                    try {
                        // 用位置参数避免 Hibernate 把 ::jsonb cast 误识别为命名参数
                        em.createNativeQuery(
                                "UPDATE component SET formulas = CAST(?1 AS jsonb), updated_at = NOW() WHERE id = ?2")
                                .setParameter(1, remapped)
                                .setParameter(2, cid)
                                .executeUpdate();
                        LOG.infof("G4 remap: 组件 %s formulas 已更新 (%d UUID remap, %d code remap)",
                                code, idMap.size(), codeMap.size());
                    } catch (Exception e) {
                        LOG.errorf("G4 remap: 更新组件 %s 失败: %s", code, e.getMessage());
                        cr.unresolved.add("UPDATE失败: " + e.getMessage());
                        // 失败时撤销 remapped 记录，避免误导
                        cr.remapped.clear();
                    }
                }
            }

            result.components.add(cr);
        }

        // 汇总
        result.totalComponents = result.components.size();
        result.remappedComponents = (int) result.components.stream()
                .filter(c -> !c.remapped.isEmpty()).count();
        result.unresolvedComponents = (int) result.components.stream()
                .filter(c -> !c.unresolved.isEmpty()).count();

        return result;
    }

    /**
     * 提取 code 的 base（去掉 __impN 后缀）。
     * COMP-0031__imp1 → COMP-0031；COMP-0031 → COMP-0031
     */
    private static String extractBase(String code) {
        if (code == null) return "";
        Matcher m = IMP_SUFFIX.matcher(code);
        return m.matches() ? m.group(1) : code;
    }

    /**
     * 从 formulas JSON 节点收集所有 cross_tab_ref.source 和 targetExpr[].source（UUID 字符串）。
     */
    private Set<String> extractAllUuidRefs(JsonNode formulas) {
        Set<String> refs = new LinkedHashSet<>();
        if (formulas == null || !formulas.isArray()) return refs;
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                collectUuidRefsFromToken(tk, refs);
            }
        }
        return refs;
    }

    /** 递归从 token 收集所有 source UUID（cross_tab_ref 的 source + targetExpr 递归） */
    private void collectUuidRefsFromToken(JsonNode tk, Set<String> refs) {
        if (!tk.isObject()) return;
        String type = tk.path("type").asText("");
        if ("cross_tab_ref".equals(type)) {
            String src = tk.path("source").asText("");
            if (!src.isBlank()) refs.add(src);
            JsonNode targetExpr = tk.path("targetExpr");
            if (targetExpr.isArray()) {
                for (JsonNode inner : targetExpr) {
                    collectUuidRefsFromToken(inner, refs);
                }
            }
        } else {
            // field 等内层 token 也可能有 source（targetExpr 内部的 field token）
            String src = tk.path("source").asText("");
            if (!src.isBlank()) refs.add(src);
        }
    }

    /**
     * 从 formulas JSON 节点收集所有 component_subtotal.component_code。
     */
    private Set<String> extractAllCodeRefs(JsonNode formulas) {
        Set<String> refs = new LinkedHashSet<>();
        if (formulas == null || !formulas.isArray()) return refs;
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                if ("component_subtotal".equals(tk.path("type").asText(""))) {
                    String code = tk.path("component_code").asText("");
                    if (!code.isBlank()) refs.add(code);
                }
            }
        }
        return refs;
    }

    /** 按 id 查组件 code（跨目录，用于判断目录外引用的 base）。 */
    @SuppressWarnings("unchecked")
    private String queryComponentCode(String uuid) {
        try {
            List<String> list = em.createNativeQuery(
                    "SELECT code FROM component WHERE id = :id")
                    .setParameter("id", UUID.fromString(uuid))
                    .getResultList();
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    // ── DirRemapResult DTO ───────────────────────────────────────────────────

    /** G4 目录级存量引用补救的返回结果。 */
    public static class DirRemapResult {
        public String directoryId;
        public boolean dryRun;
        public int totalComponents;
        public int remappedComponents;
        public int unresolvedComponents;
        public List<ComponentResult> components;

        public static class ComponentResult {
            /** 组件 code。 */
            public String code;
            /** 本次重映射的条目描述（每条 "old → new"）。 */
            public List<String> remapped;
            /** 无法解析的引用（无副本或组件不存在）。 */
            public List<String> unresolved;
        }
    }
}
