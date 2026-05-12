package com.cpq.partversion.dto;

import java.util.List;
import java.util.Map;

/**
 * 升版判定请求.
 * <p>rowsByTable: 待校验的 "新数据" 按表分组的行集. key 是表名 (mat_bom / costing_part_*),
 * value 是行列表, 每行是 columnName → value Map.
 * S2 阶段可传空 Map (propose 仅返回当前/历史版本信息);
 * S3 阶段前端从 Excel 解析后填充.
 */
public record VersionProposeRequest(
        String customerProductNo,
        String hfPartNo,
        Map<String, List<Map<String, Object>>> rowsByTable
) {}
