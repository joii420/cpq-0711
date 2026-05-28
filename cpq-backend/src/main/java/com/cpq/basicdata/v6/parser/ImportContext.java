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
     * SheetHandler 之间的共享缓存（如已写 material_master 的料号集，避免重复 upsert）。
     * key 形如 "writtenMaterialNos" / "elementBomCharCache"，约定由 Handler 自管理。
     */
    public final Map<String, Object> sharedCache = new HashMap<>();
}
