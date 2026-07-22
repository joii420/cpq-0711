package com.cpq.basicdata.v6.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * V6 导入运行上下文：每次导入跨所有 SheetHandler 共享。
 */
public class ImportContext {

    /** 报价导入注入；核价从 Excel 行读取，此字段为 null。 */
    public String customerNo;

    /** QUOTE / PRICING */
    public String systemType;

    public UUID importedBy;

    public UUID importRecordId;

    /**
     * task-0721 B2：本次导入的 pending 归属 key（延迟生效）。
     *
     * <p>报价基础数据导入发生在「创建报价单」之前（{@code BasicDataImportV6Resource.importQuote} 只有
     * {@code importRecordId}，此刻尚无 quotationId），故此处先用 {@code importRecordId} 作为临时 pending
     * 归属 key 落库（{@code QuoteImportService.processImport} 里赋值 = importRecordId）；等
     * {@code V6QuotationCommitService.createQuotation} 真正建出 Quotation 后，在同一事务内把 8 张表
     * {@code pending_quotation_id = importRecordId} 的行统一"过户"改写为真实 quotationId
     * （见该类 {@code repointPendingOwnership}）。核价侧（PRICING）导入永远不设此字段（null=现状不变）。
     */
    public UUID pendingQuotationId;

    /**
     * SheetHandler 之间的共享缓存（如已写 material_master 的料号集，避免重复 upsert）。
     * key 形如 "writtenMaterialNos" / "elementBomCharCache"，约定由 Handler 自管理。
     */
    public final Map<String, Object> sharedCache = new HashMap<>();
}
