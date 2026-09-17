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

    // task-0803 Task5⑤：导入 bundle 复用 ComponentService 的父子取值(tree_ref/tree_attr)/
    // previous_row_subtotal 校验闸(①②④)，同包 package-private 方法可直接调用。
    @Inject
    ComponentService componentService;

    // task-260915 B-4：树身份判定的全工程唯一实现。导入端只用它的**批量**入口
    // （isTreeTabBatch：固定 ≤2 条 SQL，与组件数无关），🚫 不许在循环里调单点入口 isTreeTab。
    @Inject
    TabSemanticResolver tabSemanticResolver;

    /**
     * task-260915 B-5：取数配置器产出的视图名前缀。
     * 出处 {@code BuilderService#resolveOrGenerateViewName}：{@code "builder_" + 组件id前12位}。
     * 1.0 老包里 {@code builderConfig} 根本没有这个字段可读，「这视图是不是配置器建的」只剩视图名
     * 这一个线索 —— 它<b>只影响错误文案</b>，不参与任何放行/拒绝判定，判错至多是少给一句提示。
     */
    private static final String BUILDER_VIEW_NAME_PREFIX = "builder_";

    /** task-260915 B-5：当前 bundle 格式版本（导出端 {@code ComponentExportBundle#bundleVersion} 的现值）。 */
    private static final String BUNDLE_VERSION_CURRENT = "1.1";

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

        // task-0805 R5/AC-7：bundle 内已知 Item.id 集合 + 是否存在 id 缺失的老格式条目
        // （用于判断「跨组件引用无法重映射」的 reason：BUNDLE_MISSING_ITEM_ID vs REF_NOT_IN_BUNDLE）。
        Set<String> bundleItemIds = new LinkedHashSet<>();
        boolean hasNullOrBlankId = false;
        for (ComponentExportBundle.Item it : bundle.components) {
            if (it.id != null && !it.id.isBlank()) {
                bundleItemIds.add(it.id);
            } else {
                hasNullOrBlankId = true;
            }
        }

        // ── task-260915 B-6（AC-12/AC-17）：取数配置器坐标可解析性，**整批一次**算完 ──────────
        // 🚨 只读：checkBuilderCoords 全程只 SELECT，绝不写库（预览的硬约束）。
        // 🚨 N+1：恒 1 条 SQL（semantic_tab_view 全表一次），与组件数无关；🚫 不许挪进下面的循环。
        // 下标对齐：coordJsons 与 bundle.components **等长同序**，null 元素也占位
        //   —— 结果按 task index 取用，不按任何后端返回的 key 配对（AP-37 的教训）。
        List<String> coordJsons = new ArrayList<>(bundle.components.size());
        for (ComponentExportBundle.Item it : bundle.components) {
            coordJsons.add(firstBuilderConfigJson(it));   // 纯内存，无查库
        }
        List<TabSemanticResolver.CoordCheck> coordChecks = tabSemanticResolver.checkBuilderCoords(coordJsons);

        List<ImportPreviewResult.ComponentPlan> plans = new ArrayList<>();
        List<FormulaBindingInspector.Report> bindingReports = new ArrayList<>();
        List<ImportPreviewResult.CrossRefIssue> crossRefIssues = new ArrayList<>();
        List<String> unresolvableBlockerLines = new ArrayList<>();
        int resolvedByPositionCount = 0;
        int create = 0, rename = 0, skip = 0, conflicts = 0;
        int itemIdx = -1;
        for (ComponentExportBundle.Item it : bundle.components) {
            itemIdx++;
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
                        // 预览只读、可重复调用，不消耗序列；真实顺序编号在导入(commit)时才分配。
                        p.newCode = "（导入时自动分配）";
                        p.action = "RENAME";
                        rename++;
                    }
                }
            }

            // ── R2：逐字段公式绑定去向（复现 commit 第三遍的处理顺序，全程内存副本，绝不写库）──
            FormulaBindingInspector.Report binding =
                    FormulaBindingInspector.inspect(it.code, it.name, it.fields, it.formulas);
            bindingReports.add(binding);
            p.formulaBinding = toPlanBindingItems(binding);

            // action=SKIP/ABORT 的组件根本不会落库，其 UNRESOLVABLE 不计入 blockers（只给 CREATE/RENAME）。
            boolean willImport = "CREATE".equals(p.action) || "RENAME".equals(p.action);
            for (FormulaBindingInspector.Item item : binding.items) {
                if ("RESOLVED_BY_POSITION".equals(item.status)) {
                    resolvedByPositionCount++;
                }
                if (willImport && "UNRESOLVABLE".equals(item.status)) {
                    unresolvableBlockerLines.add(
                            "组件「" + it.name + "」(" + it.code + ") 的字段「" + item.fieldName + "」" + item.message);
                }
            }

            // ── R5/AC-7：跨组件引用（cross_tab_ref.source）是否能在 bundle 内找到对应 Item.id ──
            for (String ref : extractAllUuidRefs(it.formulas)) {
                if (bundleItemIds.contains(ref)) continue; // 指向 bundle 内已知条目，导入时可被 idMap 正确重映射
                ImportPreviewResult.CrossRefIssue issue = new ImportPreviewResult.CrossRefIssue();
                issue.componentCode = it.code;
                issue.refType = "UUID";
                issue.ref = ref;
                // bundle 内存在 id 缺失的老格式条目时，无法排除该 ref 正指向那个条目 → 判定含糊；
                // 否则 bundle 内 id 齐全，unmatched 就是确凿的外部引用。
                issue.reason = hasNullOrBlankId ? "BUNDLE_MISSING_ITEM_ID" : "REF_NOT_IN_BUNDLE";
                crossRefIssues.add(issue);
            }

            // ── task-260915 B-6：配置器坐标可解析性（与 formulaBinding 并列）──────────────
            // 🚫 结果**不进 blockers、不改 canCommit**：如实报出但不阻断导入（AC-17）。
            ImportPreviewResult.BuilderCoord bc = new ImportPreviewResult.BuilderCoord();
            if (coordJsons.get(itemIdx) == null) {
                // 包里没带配置器信息：该组件本就不是配置器建的，或者这是 1.0 老包（全部落这一支）
                bc.status = "NOT_BUILDER";
            } else {
                TabSemanticResolver.CoordCheck cc = coordChecks.get(itemIdx);
                bc.status = cc.resolved() ? "RESOLVED" : "UNRESOLVABLE";
                bc.message = cc.resolved() ? null : cc.message();
            }
            p.builderCoord = bc;

            plans.add(p);
        }
        r.components = plans;
        r.crossRefIssues = crossRefIssues;

        ImportPreviewResult.Summary s = new ImportPreviewResult.Summary();
        s.total = plans.size();
        s.toCreate = create;
        s.toRename = rename;
        s.toSkip = skip;
        s.conflicts = conflicts;
        r.summary = s;

        // ── R2：全 bundle 绑定汇总（不分 action，覆盖 bundle 内全部组件）──
        FormulaBindingInspector.Report merged = FormulaBindingInspector.merge(bindingReports);
        ImportPreviewResult.BindingSummary bs = new ImportPreviewResult.BindingSummary();
        bs.totalFormulaRefs = merged.totalFormulaRefs;
        bs.unresolvable = merged.unboundCount;
        for (FormulaBindingInspector.Item item : merged.items) {
            switch (item.status) {
                case "BOUND" -> bs.bound++;
                case "RESOLVED_BY_NAME" -> bs.resolvedByName++;
                case "RESOLVED_BY_POSITION" -> bs.resolvedByPosition++;
                default -> { /* UNRESOLVABLE 已计入 unresolvable，无需重复累加 */ }
            }
        }
        r.bindingSummary = bs;

        // ── §1.2：warnings（高优先级但不阻断，前端必须无条件渲染）──
        r.warnings = new ArrayList<>();
        if (!r.checksumValid) {
            // checksum 不一致改为 warning（不再塞进 blockers；旧实现塞进 blockers 但从不影响
            // canCommit，前端却只在 !canCommit 时渲染 blockers，导致这条提示用户永远看不到）。
            r.warnings.add("⚠ checksum 校验不一致(bundle 可能被改动或损坏),请确认来源");
        }
        if (!crossRefIssues.isEmpty()) {
            r.warnings.add("⚠ " + crossRefIssues.size() + " 处跨组件引用无法重映射，详见 crossRefIssues");
        }
        if (resolvedByPositionCount > 0) {
            r.warnings.add("⚠ " + resolvedByPositionCount + " 处公式绑定按位置推导而来，请核对是否正确");
        }

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
        if (!unresolvableBlockerLines.isEmpty()) {
            // R3：默认仍阻断提交；commit 端 ignoreUnboundFormulas=true 时可显式放行（见 §2.3）。
            canCommit = false;
            r.blockers.addAll(unresolvableBlockerLines);
        }
        r.canCommit = canCommit;
        return r;
    }

    /** FormulaBindingInspector.Item → ComponentPlan.formulaBinding 的每项(去掉 componentCode/componentName)。 */
    private List<ImportPreviewResult.FormulaBindingItem> toPlanBindingItems(FormulaBindingInspector.Report report) {
        List<ImportPreviewResult.FormulaBindingItem> out = new ArrayList<>(report.items.size());
        for (FormulaBindingInspector.Item it : report.items) {
            ImportPreviewResult.FormulaBindingItem fi = new ImportPreviewResult.FormulaBindingItem();
            fi.fieldName = it.fieldName;
            fi.resolvedFormulaId = it.resolvedFormulaId;
            fi.resolvedFormulaName = it.resolvedFormulaName;
            fi.status = it.status;
            fi.message = it.message;
            out.add(fi);
        }
        return out;
    }

    /**
     * 提交导入(P3):单事务,只 INSERT 新组件 + 其 component_sql_view(全新 UUID),
     * 不 UPDATE/DELETE 任何现有数据,不绑定模板。
     *
     * <p><b>task-0803 Task5 裁决 G4（测试评审会定稿，需求说明 §11.5，2026-08-03）</b>：
     * 导入失败粒度 = <b>整包回滚</b>。本方法整体只有这一层 {@code @Transactional}
     * （默认 {@code TxType.REQUIRED}），第三遍循环里
     * {@link ComponentService#assertTreeTokenGates} 抛出的 {@code BusinessException}
     * 不会被吞掉（见下方 catch 块只重新包装、仍然抛出），会正常传播出本方法触发整个
     * 事务回滚——bundle 内已在第一遍 INSERT 的全部 Component/ComponentSqlView 一并撤销，
     * 不会出现"部分组件导入成功、部分因校验闸拒绝"的半成品状态。这是明确裁决，不是
     * 恰好如此的偶然行为；后续若要改成"按组件粒度部分提交"需先过架构评审。
     *
     * @param ignoreMissingDeps true 时即使依赖缺失也继续(相关字段运行时取数可能失败)
     * @param ignoreUnboundFormulas task-0805 R3：true 时即使存在未显式绑定的 FORMULA 字段
     *        也继续(默认 false 时行为逐字节不变——{@link FormulaIdBinder#validateExplicitBinding}
     *        抛出 400，单事务整体回滚)。放行时不足以固化的字段会被记入
     *        {@link ImportCommitResult#unboundWarnings}，供该组件在组件管理中标记「待绑定」。
     */
    @Transactional
    public ImportCommitResult commit(UUID targetDirId, ComponentExportBundle bundle,
                                     String conflictPolicy, boolean ignoreMissingDeps,
                                     boolean ignoreUnboundFormulas) {
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

        ImportCommitResult result = new ImportCommitResult();
        result.targetDirectoryId = targetDirId.toString();
        result.targetDirectoryName = dir.name;
        result.conflictPolicy = policy;
        result.created = new ArrayList<>();
        result.skipped = new ArrayList<>();
        result.unboundWarnings = new ArrayList<>();
        int sqlViews = 0;

        // ── 第一遍：创建所有新组件，同时收集 idMap / codeMap ─────────────────
        // idMap:  原组件 id（bundle Item.id）→ 新副本 id（新建后的 UUID 字符串）
        // codeMap: 原组件 code（bundle Item.code）→ 新副本 finalCode
        // 仅 CREATE 的组件进 map；SKIP 的组件不进 map（引用无法重映射）
        Map<String, String> idMap = new HashMap<>();
        Map<String, String> codeMap = new HashMap<>();
        // 记录新建的组件实体，供第二遍重写 formulas
        List<Component> createdComponents = new ArrayList<>();
        // task-260915 B-5：新建组件 id → 其 bundle 条目（第三遍拼报错文案用）
        Map<UUID, ComponentExportBundle.Item> itemByComponentId = new HashMap<>();

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
                finalCode = nextSequentialComponentCode();
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
            // 恢复行键（导出端早期遗漏；多行可编辑组件缺行键会导致渲染撞键/editRows 塌缩）
            if (it.rowKeyFields != null && it.rowKeyFields.isArray() && it.rowKeyFields.size() > 0) {
                c.rowKeyFields = nodeToJson(it.rowKeyFields);
            }
            // task-0721 页签类型属性：tabType/partNoField/partNameField；BOM 树页签自动同步 bomRecursiveExpand
            c.tabType = it.tabType;
            c.partNoField = it.partNoField;
            c.partNameField = it.partNameField;
            // task-0722 行排序列
            c.sortField = it.sortField;
            // task-260915 B-3：树表展示配置。源为空 → 保持 NULL（该列可空），
            // 不能走 nodeToJson —— 它把 null 落成 "[]"，会把「没配树」写成「配了个空数组」。
            if (it.treeConfig != null && !it.treeConfig.isNull()) {
                c.treeConfig = it.treeConfig.toString();
            }
            // task-260915 B-3：bom_recursive_expand。
            // 优先用包里带来的值（1.1 包携带源库真值，含 false —— false 也是有效值，不能当"没带"）；
            // 包里为 null（1.0 老包）时才回落 task-260904 的 tab_type 推导。
            // ⚠️ 回落分支必须保留：老包没有这个字段，删掉它老包导入后 BOM 页签就不递归展开了。
            if (it.bomRecursiveExpand != null) {
                c.bomRecursiveExpand = it.bomRecursiveExpand;
            } else if (TabSemanticResolver.isLegacyTreeTabType(it.tabType)) {
                c.bomRecursiveExpand = Boolean.TRUE;
            }
            // task-260915 B-3：元素价格三列。接价格策略的组件缺这三列会在目标库保存期被 400 硬拒。
            c.elementCodeField = it.elementCodeField;
            c.elementPriceField = it.elementPriceField;
            c.elementCurrencyField = it.elementCurrencyField;
            c.fields = nodeToJson(it.fields);
            c.formulas = nodeToJson(it.formulas);
            // excel_columns 列 NOT NULL；nodeToJson 已把 null/缺失 → "[]"
            // ⚠️ 这里写的是**源库原值**；其中 tabs[].tabKey 的跨页签组件引用由第二遍
            //    FormulaRefRemapper.remapExcelColumns 重映射（task-260915 B-11）——
            //    必须等第一遍全部建完、idMap 收集齐了才能做，理由同 formulas 那路。
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
            // task-260915 B-5：新建组件 → 它在包里的原始条目。第三遍拼错误文案时要回头看
            // 「这个组件在包里带没带配置器信息」，而 Component 实体上没有这个信息。
            itemByComponentId.put(c.id, it);

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
                    // task-260915 B-3：status 照搬源值；**只有老包（1.0，无此字段）才默认 ACTIVE**。
                    // 改动前是无条件硬编码 "ACTIVE" —— 源库里已停用的视图会被悄悄激活。
                    v.status = sv.status == null ? "ACTIVE" : sv.status;
                    v.description = sv.description;
                    // task-260915 B-3：取数配置器配置 + 编译器版本（成对）。原样落库，不规范化。
                    // 它们是 TabSemanticResolver 分支① 的全部输入 —— 恢复了它们，导入后的组件
                    // 才保住树身份、才能在取数配置器里继续编辑。源为空 → 保持 NULL（两列均可空）。
                    if (sv.builderConfig != null && !sv.builderConfig.isNull()) {
                        v.builderConfig = sv.builderConfig.toString();
                    }
                    v.builderVersion = sv.builderVersion;
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

        // ── 第二遍：对每个新建组件重写**跨组件引用**（formulas + excel_columns）──────────
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
                // task-260915 B-11（AC-21）：跨组件引用不止在 formulas 里 —— EXCEL 组件的
                // excel_columns.tabs[].tabKey 指向兄弟页签组件的 id，此前从未重映射，
                // 导入后仍指向**源目录**的旧组件（跨机器搬运时直接是悬空引用）。
                // 🚫 纯内存 JSON 变换，无查库；与上面 formulas 那路共用同一份 idMap。
                String remappedExcel = FormulaRefRemapper.remapExcelColumns(c.excelColumns, idMap);
                if (remappedExcel != null && !remappedExcel.equals(c.excelColumns)) {
                    c.excelColumns = remappedExcel;
                }
            }
        }

        // ── task-260915 B-4：树身份**批量**判定（必须在进第三遍循环之前一次性算完）──────
        // 🚫 循环里逐个调 componentService.assertTreeTokenGatesFor / TabSemanticResolver.isTreeTab
        //    = N+1（每个组件各查一次 component_sql_view）。isTreeTabBatch 自带承诺：固定 ≤2 条 SQL，
        //    与入参组件数无关（5 个组件的包和 10 个组件的包发出的条数完全相等）。
        // 前提：第一遍 persist() 的 ComponentSqlView 必须能被这里的 Panache 查询看见。成立 ——
        //    同事务内 Hibernate 对 JPQL 查询做 auto-flush（FlushModeType.AUTO），持久化上下文里
        //    待 INSERT 的 ComponentSqlView 会先落库再查。已实测，见
        //    Task260915ImportTreeJudgementTest#persistedSqlViewIsVisibleToBatchResolverInSameTx。
        Map<UUID, String> tabTypeByComponentId = new LinkedHashMap<>();
        for (Component c : createdComponents) {
            tabTypeByComponentId.put(c.id, c.tabType);
        }
        Map<UUID, Boolean> treeFlagByComponentId = tabSemanticResolver.isTreeTabBatch(tabTypeByComponentId);

        // ── 第三遍：BL-0098 公式 id 补齐 + 字段绑定固化 + 显式绑定校验 ──────────
        // 必须在第二遍 FormulaRefRemapper.remap 之后：remap 整体重写 c.formulas，
        // 放在它之前补的 id 有被洗掉的风险。
        // 老 bundle 不带 id/formula_id → 这里按现役回退链固化，行为与导入前一致；
        // 新 bundle 自带 id（作用域=组件内，无需全局唯一）→ 原样保留，不重新生成。
        for (Component c : createdComponents) {
            try {
                List<Map<String, Object>> fieldList = MAPPER.readValue(
                    c.fields == null || c.fields.isBlank() ? "[]" : c.fields,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                List<Map<String, Object>> formulaList = MAPPER.readValue(
                    c.formulas == null || c.formulas.isBlank() ? "[]" : c.formulas,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

                FormulaIdBinder.ensureFormulaIds(formulaList);
                FormulaIdBinder.bindFormulaIdsToFields(fieldList, formulaList);
                // task-0805 R3：默认行为逐字节不变——validateExplicitBinding 直接抛出 IllegalArgumentException，
                // 由本方法下面的 catch 块加上下文前缀后继续往外抛（整包回滚）。
                // ignoreUnboundFormulas=true 时改走 listUnboundFormulaFields 记录清单放行，
                // 两个分支复用同一份「谁未绑定」的判断（FormulaIdBinder 内部单一口径），
                // 不使用 try/catch(IllegalArgumentException) 吞异常——那会连带吞掉本循环里其它来源的 IAE。
                if (ignoreUnboundFormulas) {
                    for (String fieldName : FormulaIdBinder.listUnboundFormulaFields(fieldList)) {
                        ImportCommitResult.UnboundWarning w = new ImportCommitResult.UnboundWarning();
                        w.componentCode = c.code;
                        w.fieldName = fieldName;
                        result.unboundWarnings.add(w);
                    }
                } else {
                    FormulaIdBinder.validateExplicitBinding(fieldList);
                }

                c.fields = MAPPER.writeValueAsString(fieldList);
                c.formulas = MAPPER.writeValueAsString(formulaList);
                // Panache 实体在 @Transactional 方法内已 managed，赋值后 Hibernate 脏检查自动 flush

                // task-0803 Task5⑤：同一循环里跑闸①②④（父子取值 tabType 联动 + BOM 禁 PREV），
                // 不留导入这条路径绕过配置期校验的口子。c.tabType 已在第一遍(persist 前)写入。
                // task-260915 B-4：改用**双判据**（与组件新建/更新同口径）。上面那条
                // 「导入 bundle 不携带 builder_config ⇒ 恒走分支②」的旧注释已随 B-1~B-3 作废 ——
                // 1.1 包携带 builder_config/builder_version，导入建出的组件同样可能走分支①。
                // 判据整批算在循环外（treeFlagByComponentId），循环体内零查库。
                componentService.assertTreeTokenGatesPrecomputed(
                        Boolean.TRUE.equals(treeFlagByComponentId.get(c.id)),
                        c.tabType, c.formulas, c.fields,
                        legacyBundleTreeHint(bundle, itemByComponentId.get(c.id)));
            } catch (BusinessException e) {
                // 校验闸门抛出的是业务语义 400（非结构解析失败），保留原始 code，只加上下文前缀。
                throw new BusinessException(e.getCode(),
                    "组件「" + c.name + "」(" + c.code + ") 导入失败：" + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "组件「" + c.name + "」(" + c.code + ") 导入失败：" + e.getMessage(), e);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "组件「" + c.name + "」(" + c.code + ") 公式 id 处理失败：" + e.getMessage(), e);
            }
        }

        result.createdCount = result.created.size();
        result.skippedCount = result.skipped.size();
        result.unboundCount = result.unboundWarnings.size();
        result.sqlViewsCreated = sqlViews;
        return result;
    }

    /**
     * task-260915 B-6：取该 bundle 条目里<b>第一条带 {@code builderConfig} 的视图</b>的 JSON 文本；
     * 没有则返回 {@code null}（= 该组件不是取数配置器建的，或包是 1.0 老格式根本不带这个字段）。
     *
     * <p>「取第一条」与 {@code TabSemanticResolver#builderSemantics} 的口径一致：同一组件理论上
     * 只有一条 builder 视图。纯内存，不查库。
     */
    private static String firstBuilderConfigJson(ComponentExportBundle.Item it) {
        if (it == null || it.sqlViews == null) return null;
        for (ComponentExportBundle.SqlView sv : it.sqlViews) {
            if (sv.builderConfig != null && !sv.builderConfig.isNull()) {
                return sv.builderConfig.toString();
            }
        }
        return null;
    }

    /**
     * task-260915 B-5（AC-11）：闸②因「非树页签」拒绝时追加的定位提示。
     *
     * <p>只在<b>三个条件同时成立</b>时给出非空提示，其余一律返回 {@code null}（文案逐字不变）：
     * <ol>
     *   <li>包版本 &lt; 1.1（{@code bundleVersion} 缺失或不等于 {@value #BUNDLE_VERSION_CURRENT}）；</li>
     *   <li>该组件至少有一条<b>取数配置器建的</b>视图（视图名以 {@value #BUILDER_VIEW_NAME_PREFIX} 开头）；</li>
     *   <li>那条视图的 {@code builderConfig} 为空（= 包里确实没带配置器信息）。</li>
     * </ol>
     *
     * <p>为什么要这句话：源库里这个组件的树身份记在 {@code builder_config.tabType} 里
     * （{@code component.tab_type} 在新模型下天然为 NULL），1.0 老包把这个唯一凭据丢了 ⇒
     * 导入端无论如何都判不出它是树页签。此时只报「当前组件 tabType=(未配置)」会把用户引向
     * 「去改 tab_type」这条死路 —— 真正的出路是在源库升级后重新导出。
     *
     * <p>⚠️ 本方法<b>纯内存</b>（只读 bundle 对象），不查库；在第三遍循环里逐个调用不构成 N+1。
     */
    private String legacyBundleTreeHint(ComponentExportBundle bundle, ComponentExportBundle.Item item) {
        if (bundle == null || item == null) return null;
        if (BUNDLE_VERSION_CURRENT.equals(bundle.bundleVersion)) return null;   // 1.1 包不给这句
        if (item.sqlViews == null || item.sqlViews.isEmpty()) return null;
        boolean builderViewWithoutConfig = false;
        for (ComponentExportBundle.SqlView sv : item.sqlViews) {   // 纯内存遍历，无查库
            boolean isBuilderView = sv.sqlViewName != null
                    && sv.sqlViewName.startsWith(BUILDER_VIEW_NAME_PREFIX);
            boolean configMissing = sv.builderConfig == null || sv.builderConfig.isNull();
            if (isBuilderView && configMissing) {
                builderViewWithoutConfig = true;
                break;
            }
        }
        if (!builderViewWithoutConfig) return null;
        return "该组件的页签类型是在「取数配置器」里配的（树身份记在配置器信息里，不在 tabType 列），"
             + "而这个导入包是旧格式（bundleVersion "
             + (bundle.bundleVersion == null || bundle.bundleVersion.isBlank()
                     ? "缺失" : bundle.bundleVersion)
             + "，不含配置器信息），导入端无从得知它是树页签。"
             + "请在源库升级到含本次修复的版本后重新导出，再导入本包。";
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
     * 取系统组件编号序列的下一个值，格式 COMP-####（与「新建组件」同源，走 component_code_seq）。
     * 序列全局唯一且单调递增，天生不撞库内既有 code，无需 reserved 防撞循环。
     *
     * <p>导入冲突(RENAME)时用此分配干净顺序编号，取代历史上的 {@code base__impN} 后缀
     * （方案 A，2026-07-20；见 docs/superpowers/specs/2026-07-20-导入组件顺序编号-design.md）。
     * 存量 {@code __impN} 副本不受影响（{@code IMP_SUFFIX} 解析仍保留）。
     */
    private String nextSequentialComponentCode() {
        Long seq = (Long) em.createNativeQuery("SELECT nextval('component_code_seq')").getSingleResult();
        return String.format("COMP-%04d", seq);
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
