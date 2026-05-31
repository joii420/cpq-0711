package com.cpq.component.dto;

import java.util.List;

/**
 * 组件目录导入 **提交(P3)** 结果。
 *
 * <p>单事务执行,只 INSERT 新组件 + 其 component_sql_view(全新 UUID),不动任何现有数据。
 * 设计见 docs/PRD-v3.md §5.4.6。
 */
public class ImportCommitResult {

    public String targetDirectoryId;
    public String targetDirectoryName;
    public String conflictPolicy;

    public int createdCount;
    public int skippedCount;
    public int sqlViewsCreated;

    public List<CreatedItem> created;
    /** 被跳过的原始 code(SKIP 策略下冲突项)。 */
    public List<String> skipped;

    public static class CreatedItem {
        /** bundle 里的原始 code。 */
        public String originalCode;
        /** 实际落库的 code(重命名时与 original 不同)。 */
        public String finalCode;
        /** 新建组件的 id。 */
        public String componentId;
        public boolean renamed;
        public int sqlViewCount;
    }
}
