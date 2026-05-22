package com.cpq.quotation.refdata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * V212: GET /api/cpq/quotations/{qid}/ref-data/snapshot 响应元素 (非 DRAFT 快照数据).
 *
 * <p>JSON shape 与 BoundGvViewDTO 完全一致, 仅来源不同:
 * 直接从 quotation.bound_global_variables_snapshot JSONB 反序列化.
 * snapshotAt 对应 JSONB 中的 snapshotAt 字段 (与 BoundGvViewDTO.fetchedAt 同义, 名称对齐快照结构).
 *
 * <p>前端可用同一 React 组件渲染两种来源, 仅 fetch URL 不同.
 */
public class BoundGvSnapshotItem {

    public String code;
    public String name;

    /** LOOKUP_TABLE | SCALAR */
    public String varType;

    public String unit;
    public int displayOrder;

    /** 快照时刻 (对应 JSONB snapshotAt 字段) */
    public OffsetDateTime snapshotAt;

    /** 列顺序: key_columns + [value_column] */
    public List<String> columns;

    /** 快照行数据 */
    public List<Map<String, Object>> rows;
}
