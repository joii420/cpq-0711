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
        public Boolean includeChildParts;
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
        public boolean isLegacyHandwritten;
        public boolean isStale;
        public int currentCompilerVersion;
    }

    public static class SaveResponse {
        public int builderVersion;
        public int affectedTemplates;
    }
}
