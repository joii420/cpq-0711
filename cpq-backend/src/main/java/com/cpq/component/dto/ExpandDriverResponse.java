package com.cpq.component.dto;

import java.util.List;
import java.util.Map;

/**
 * Y1.5 组件按 driver 路径展开响应。
 *
 * <pre>
 * {
 *   "rowCount": 3,
 *   "driverPath": "mat_bom[bom_type='INCOMING']",
 *   "rows": [
 *     {
 *       "driverRow": {"input_material_no":"C001","loss_rate":0.05, ...},
 *       "basicDataValues": {
 *         "{mat_part.unit_weight}": 12.5,
 *         "{mat_bom[bom_type='ELEMENT'].composition_pct}": 0.85
 *       }
 *     }
 *   ]
 * }
 * </pre>
 */
public class ExpandDriverResponse {

    public int rowCount;
    public String driverPath;
    public List<Row> rows;

    public static class Row {
        /** driver 路径返回的整行(用于客户端记 K-V) */
        public Map<String, Object> driverRow;
        /** key = 字段原始路径(含花括号),value = 求值结果(可能为 null/原值/FormulaError 字符串) */
        public Map<String, Object> basicDataValues;
    }
}
