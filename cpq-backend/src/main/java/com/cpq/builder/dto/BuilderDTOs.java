package com.cpq.builder.dto;

import com.cpq.builder.compiler.BuilderConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * builder 端点族的请求/响应 DTO（task-260819 B-11/B-12/B-13/B-15/B-20，api.md §2）。
 *
 * <p>🚫 全部裸体，不套 {@code ApiResponse} 信封（api.md §1.5③）。
 */
public final class BuilderDTOs {
    private BuilderDTOs() {}

    /** POST /compile、POST /inspect 的请求体 = builder_config 本身（api.md §1.5①），直接复用 {@link BuilderConfig}。 */

    /** POST /preview 的请求体 = builder_config + 预览参数（api.md §1.5②）。 */
    public static class PreviewRequest extends BuilderConfig {
        public String customerCode;
        public String partNo;
        // D-43（2026-08-21 主线裁决）：闭包开关的唯一位置是 builder_config.switches.includeChildParts
        // （BuilderConfig#includeChildParts()，SemanticCompiler 只读这一处）——这里原来重复声明过
        // 一个顶层 includeChildParts 字段，Jackson 会把它填进来但没有任何代码消费，谁按旧版
        // api.md §1.5②示例传顶层字段，闭包就静默失效（不报错、无 WITH RECURSIVE）。已删除，
        // 不要在这里重新加同名字段。
    }

    /** PUT / 的请求体 = builder_config + confirmedImpact（api.md §2.4）。 */
    public static class SaveRequest extends BuilderConfig {
        public boolean confirmedImpact;
    }

    public static class CompileResponse {
        public String sql;
        public List<String> declaredColumns;
        public List<String> requiredVariables;
        public List<String> grain;
        public boolean rewriterCompatible;
        public List<String> warnings;
    }

    public static class PreviewResponse {
        public int rowCount;
        public List<String> columns = new ArrayList<>();
        public List<Map<String, Object>> rows = new ArrayList<>();
        public long elapsedMs;
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }

    public static class Diagnostic {
        public String level; // WARN | INFO
        public String code;
        public String column;
        public String message;

        public Diagnostic() {}
        public Diagnostic(String level, String code, String column, String message) {
            this.level = level; this.code = code; this.column = column; this.message = message;
        }
    }

    public static class InspectItem {
        public String level; // ERR | WARN
        public String code;
        public String message;

        public InspectItem() {}
        public InspectItem(String level, String code, String message) {
            this.level = level; this.code = code; this.message = message;
        }
    }

    public static class InspectResponse {
        public boolean blocked;
        public List<InspectItem> items = new ArrayList<>();
    }

    public static class GetBuilderResponse {
        public BuilderConfig builderConfig;
        public Integer builderVersion;
        /**
         * D-46（2026-08-21 主线裁决）：三态收窄——{@code viewState=="LEGACY_HANDWRITTEN"} 的语法糖，
         * 不再等价于 {@code builderConfig==null}（原契约把"全新组件"和"存量手写"两态压成了一态，
         * 导致新建组件也被误判成手写引导页）。保留字段名兼容前端旧代码。
         */
        public boolean isLegacyHandwritten;
        public boolean isStale;
        public int currentCompilerVersion;
        /** NEW（无任何 sql_view 行）| LEGACY_HANDWRITTEN（有行但 builder_config 为空）| BUILDER（builder_config 非空）。 */
        public String viewState;
    }

    public static class SaveResponse {
        public int builderVersion;
        public int affectedTemplates;
    }
}
