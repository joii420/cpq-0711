package com.cpq.quotation.refdata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * V212: GET /api/cpq/quotations/{qid}/ref-data 响应元素 (DRAFT 报价单实时数据).
 *
 * <p>columns 显式给出列顺序 (取 key_columns + [value_column]), 前端不依赖 JSON key 顺序.
 * fetchedAt 记录实时拉取时刻.
 */
public class BoundGvViewDTO {

    public String code;
    public String name;

    /** LOOKUP_TABLE | SCALAR */
    public String varType;

    public String unit;
    public int displayOrder;

    /** 实时拉取时刻 */
    public OffsetDateTime fetchedAt;

    /** 列顺序: key_columns + [value_column] */
    public List<String> columns;

    /** 整表行数据 */
    public List<Map<String, Object>> rows;
}
