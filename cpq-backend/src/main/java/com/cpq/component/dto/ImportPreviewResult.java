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

    /** 是否允许提交(P3)。缺依赖(默认阻止) 或 ABORT 策略下有冲突 → false。 */
    public boolean canCommit;
    /** 阻止提交的原因(人类可读)。 */
    public List<String> blockers;

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
}
