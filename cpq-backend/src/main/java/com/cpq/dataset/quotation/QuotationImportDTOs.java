package com.cpq.dataset.quotation;

import com.cpq.dataset.dto.DsValidationError;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * task-260907 · 报价数据导入建单专用 DTO 集（api.md §1 / §2 / §3）。
 *
 * <p>刻意集中在一个文件里：四个 DTO 都是纯数据载体、只被本包的两个 Resource 方法使用，
 * 分四个文件只会让「api.md 的形状」散落在四处、比对契约时要来回翻。
 *
 * <p>🚨 <b>与既有 {@code DatasetImportErrorsDTO} / {@code DatasetSheetSummaryDTO} 的字段名不同</b>：
 * api.md §2 约定的是 {@code sheetName / rowNum / columnLabel / kind}，而既有两个 DTO 用的是
 * {@code sheet / row / column / versioned}。本文件的两个 View 类只做<b>出参映射</b>，
 * 🚫 不去改那两个共用 DTO —— 它们同时服务 {@code POST /dataset/{dataset}/import}（【基础资料维护】
 * 共用，AC-15 要求「读写行为与改动前逐字一致」），改字段名会直接打断维护页签。
 */
public final class QuotationImportDTOs {

    private QuotationImportDTOs() {}

    /** api.md §1 响应：导入已受理，前端转轮询 §2。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class StartResult {
        public UUID importRecordId;
        public String systemType;
        public String status;

        public StartResult(UUID importRecordId, String systemType, String status) {
            this.importRecordId = importRecordId;
            this.systemType = systemType;
            this.status = status;
        }
    }

    /** api.md §2 的 {@code progress}。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ProgressView {
        public int done;
        public int total;
        public String current;
    }

    /**
     * api.md §2 的 {@code summary[]}。
     * {@code kind=PLAIN} 出 inserted/updated；{@code kind=VERSIONED} 出 axisCount/created/upgraded/unchanged。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class SheetSummaryView {
        public String sheetName;
        public String kind;
        public Integer axisCount;
        public Integer inserted;
        public Integer updated;
        public Integer created;
        public Integer upgraded;
        public Integer unchanged;
    }

    /**
     * api.md §2 的 {@code errors[]}。
     *
     * <p>⚠️ <b>{@code value} 恒为 null（契约缺口，已报主线）</b>：api.md §2 的示例带 {@code value}
     * （出错单元格的原始值），但既有 {@link DsValidationError} 只有 {@code sheet/row/column/reason}
     * 四个字段，产出它的 {@code DatasetImportValidator} 是【基础资料维护】导入共用的类。
     * 要补 {@code value} 就得改那两个共用件，届时 {@code POST /dataset/{dataset}/import} 的 400
     * 响应体会多一个字段 —— 与 AC-15「读写行为与改动前逐字一致」冲突。
     * ⇒ 本任务<b>不改</b>，{@code value} 留空（{@code NON_NULL} 下不出现在 JSON 里）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ValidationErrorView {
        public String sheetName;
        public int rowNum;
        public String columnLabel;
        public String value;      // 恒 null，见类注释
        public String reason;
    }

    /** api.md §2 响应。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class StatusResult {
        public UUID importRecordId;
        public String systemType;
        public String status;
        public String originalFileName;
        public Integer totalRows;
        public Integer successRows;
        public Integer failedRows;
        public OffsetDateTime createdAt;
        public UUID quotationId;          // 已按 §3 建过单时回带，供前端幂等重入判断
        public ProgressView progress;
        public List<SheetSummaryView> summary;
        public List<ValidationErrorView> errors;
        public String message;            // FAILED 时的整体说明（如「共 N 处问题，本次未写入任何数据」）
    }

    /** api.md §3 请求。 */
    public static final class CreateQuotationRequest {
        public UUID importRecordId;
        public UUID customerId;
        public String name;
        public UUID categoryId;
        public UUID customerTemplateId;
        public UUID costingTemplateId;
    }

    /** api.md §3 响应。{@code materializing} 恒 true（物化转后台，D-5 教训：不许靠 cardValuesReady 猜）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class CreateQuotationResult {
        public UUID quotationId;
        /**
         * 🆕 D-31：报价单号（如 {@code QT-20260907-0003}）。前端两处要显示。
         * <p>建单响应里直接给，前端就不必为了拿一个字符串额外打一次
         * {@code GET /quotations/{id}} —— 那在 1845 行的单上是重载荷请求。
         * <p>⚠️ 幂等重入分支同样要有值（见 {@code DatasetQuotationCommitService}），
         * 否则「重复提交后单号突然空了」。
         */
        public String quotationNumber;
        public UUID importRecordId;
        public int lineItemsCount;
        public boolean materializing;
        /** 幂等重入（B-9 / AC-12）：true 表示本次未新建，返回的是既有单。 */
        public boolean reentered;

        public CreateQuotationResult(UUID quotationId, String quotationNumber,
                                     UUID importRecordId, int lineItemsCount) {
            this.quotationId = quotationId;
            this.quotationNumber = quotationNumber;
            this.importRecordId = importRecordId;
            this.lineItemsCount = lineItemsCount;
        }
    }
}
