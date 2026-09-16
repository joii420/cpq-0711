package com.cpq.component.dto;

import java.util.List;

/**
 * 组件目录导入 **预览(dry-run)** 结果(P2,不写库)。
 *
 * <p>给出:依赖存在性校验 + 按冲突策略算出的每组件动作计划 + 是否可提交(canCommit)。
 * 设计见 docs/PRD-v3.md §5.4.6。
 */
public class ImportPreviewResult {

    public String bundleVersion;
    /** bundle.checksum 与重算值是否一致(false=可能被改动/损坏, 警告但不一定阻止)。 */
    public boolean checksumValid;
    public String targetDirectoryId;
    public String targetDirectoryName;
    /** 实际采用的冲突策略:RENAME / SKIP / ABORT。 */
    public String conflictPolicy;

    public Summary summary;
    public List<ComponentPlan> components;
    public DependencyCheck dependencies;

    /** 是否允许提交(P3)。缺依赖(默认阻止) 或 ABORT 策略下有冲突 或 存在 UNRESOLVABLE 公式绑定 → false。 */
    public boolean canCommit;
    /** 阻止提交的原因(人类可读)。 */
    public List<String> blockers;
    /** task-0805 §1.2：高优先级但不阻断提交的提示(checksum 不一致 / 跨组件引用无法重映射 /
     *  按位置推导的绑定)。前端必须**无条件**渲染，不受 canCommit 真假影响。 */
    public List<String> warnings;
    /** task-0805 R2：全 bundle 的公式绑定汇总(不区分 action，覆盖 bundle 内全部组件)。 */
    public BindingSummary bindingSummary;
    /** task-0805 R5/AC-7：bundle 内跨组件引用因 Item.id 缺失/引用目标不在 bundle 内而无法重映射的清单。 */
    public List<CrossRefIssue> crossRefIssues;

    public static class Summary {
        public int total;
        public int toCreate;
        public int toRename;
        public int toSkip;
        public int conflicts;
    }

    public static class ComponentPlan {
        public String code;
        public String name;
        /** CREATE / RENAME / SKIP。 */
        public String action;
        /** RENAME 时的新 code(加后缀)。 */
        public String newCode;
        /** 与现有组件 code 冲突。 */
        public boolean conflict;
        public int sqlViewCount;
        /** task-0805 R2：该组件逐 FORMULA 字段「将绑到哪条公式」。action=SKIP 的组件也照常给出
         *  (供核对)，但其 UNRESOLVABLE 不计入 blockers——该组件根本不会被导入。 */
        public List<FormulaBindingItem> formulaBinding;
        /** task-260915 B-6（AC-12/AC-17）：该组件的取数配置器坐标在目标库能否解析到。
         *  与 {@link #formulaBinding} 并列，但**不阻断**——见 {@link BuilderCoord} 的警示。 */
        public BuilderCoord builderCoord;
    }

    /**
     * task-260915 B-6：导入预览的「取数配置器坐标可解析性」报告项（AC-12/AC-17）。
     *
     * <p>回答的是：包里这个组件的配置器坐标（{@code builder_config} 的
     * {@code tabType}/{@code variantKey}/{@code dialect} 三段），在<b>目标库</b>的
     * {@code semantic_tab_view} 里找不找得到。跨库语义图有差异时会找不到。
     *
     * <p>🚫 <b>UNRESOLVABLE 不进 blockers、不让 canCommit 变 false</b>（AC-17）：
     * 如实报出但<b>不阻断导入</b>，{@code builder_config} 原样落库，由用户决定是否重绑。
     *
     * <p>⚠️ 本项的 {@code UNRESOLVABLE} 与 {@link FormulaBindingItem#status} 的
     * {@code UNRESOLVABLE} 是<b>同名不同义</b>的两个值：后者<b>会</b>拦提交，本项<b>不会</b>。
     * 前端不要复用同一套拦截逻辑。
     */
    public static class BuilderCoord {
        /**
         * {@code NOT_BUILDER}   = 该组件没有配置器视图（包里 builderConfig 为 null，含全部 1.0 老包）；
         * {@code RESOLVED}      = 三段坐标在目标库 semantic_tab_view 能解析到；
         * {@code UNRESOLVABLE}  = 解析不到，{@link #message} 说明缺哪个坐标。
         */
        public String status;
        /** UNRESOLVABLE 时给人话原因，其余为 null。 */
        public String message;
    }

    public static class DependencyCheck {
        public List<DepItem> globalVariables;
        public List<DepItem> datasources;
        public int missingCount;
    }

    public static class DepItem {
        public String code;
        public boolean exists;
    }

    /** task-0805 R2：全 bundle 公式绑定去向汇总。 */
    public static class BindingSummary {
        public int totalFormulaRefs;
        public int bound;
        public int resolvedByName;
        public int resolvedByPosition;
        public int unresolvable;
    }

    /** task-0805：单条绑定检查结果，与导出 bundle.bindingReport.items 同构，去掉 componentCode/componentName
     *  (已在 ComponentPlan 外层给出)。 */
    public static class FormulaBindingItem {
        public String fieldName;
        public String resolvedFormulaId;
        public String resolvedFormulaName;
        /** BOUND | RESOLVED_BY_NAME | RESOLVED_BY_POSITION | UNRESOLVABLE */
        public String status;
        public String message;
    }

    /** task-0805 R5/AC-7：跨组件引用无法在导入时重映射。 */
    public static class CrossRefIssue {
        public String componentCode;
        /** 目前恒为 "UUID"。 */
        public String refType;
        public String ref;
        /** BUNDLE_MISSING_ITEM_ID | REF_NOT_IN_BUNDLE */
        public String reason;
    }
}
