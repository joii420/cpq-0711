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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 组件目录 **导入预览(P2,dry-run)** 服务。
 *
 * <p>只读校验 + 生成计划,**绝不写库**:依赖存在性校验 + code 冲突计划 + checksum 校验。
 * 提交(P3)单独实现。设计见 docs/PRD-v3.md §5.4.6。
 */
@ApplicationScoped
public class ComponentImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
}
