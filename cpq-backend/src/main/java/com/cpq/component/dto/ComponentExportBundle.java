package com.cpq.component.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 组件目录导出 bundle（P1,只读导出产物）。
 *
 * <p>导出某目录**直属**组件的完整配置 + 依赖清单。设计见 docs/PRD-v3.md §5.4.6。
 * tempId 不在导出端使用(P1 仅导出);导入端(P2/P3)按 code 做冲突处理 + 重映射。
 */
public class ComponentExportBundle {

    /** bundle 格式版本,导入端据此判断兼容性。 */
    public String bundleVersion = "1.0";
    /** 导出时间(ISO-8601)。 */
    public String exportedAt;
    /** 来源目录信息(仅供追溯,导入时不依赖)。 */
    public Source source;
    /** 该目录直属组件(本期不递归子目录)。 */
    public List<Item> components;
    /** 依赖清单:组件引用但不随 bundle 走的外部对象,供导入端校验是否存在。 */
    public Dependencies dependencies;
    /** 内容校验和(sha256,基于 source+components+dependencies 的规范 JSON),防损坏/篡改。 */
    public String checksum;
    /** task-0805 R1：公式绑定完整性只读扫描报告。顶层可选字段，**不参与 checksum 计算**
     *  （computeChecksum 只覆盖 source+components+dependencies）；导出永不因此阻断。
     *  老 bundle 反序列化时此字段为 null，导入端可容忍。 */
    public BindingReport bindingReport;

    public static class Source {
        public String directoryId;
        public String directoryName;
    }

    public static class Item {
        /** 原组件 id（UUID 字符串），供导入端重映射跨组件引用（cross_tab_ref.source 等）。
         *  老 bundle（无此字段）反序列化后为 null，导入端需做降级处理。 */
        public String id;
        public String code;
        public String name;
        public String componentType;
        public Integer columnCount;
        public String status;
        public String dataDriverPath;
        /** task-0721 页签类型属性：tabType(BOM/材质元素/零件/外购件/主件) + 料号列/料号名称列。
         *  导入端须一并恢复,否则类型判定/加叶子失据。老 bundle 无此字段=null。 */
        public String tabType;
        public String partNoField;
        public String partNameField;
        /** task-0722：多行页签「行排序列」字段名(原 sort_field)。导入端须一并恢复,
         *  否则导入后行排序丢失→项次/序号不按数字正序。老 bundle 无此字段=null。 */
        public String sortField;
        /** 行键字段名列表(原 row_key_fields JSONB)。多行可编辑组件的行唯一键；
         *  导入端须一并恢复,否则导入后行键丢失→多材质/多工序等场景撞键。老 bundle 无此字段=null。 */
        public JsonNode rowKeyFields;
        /** 字段定义(原 JSONB,内嵌为真实 JSON 节点)。 */
        public JsonNode fields;
        /** 公式定义(原 JSONB)。 */
        public JsonNode formulas;
        /** EXCEL 组件列定义(原 JSONB,内嵌为真实 JSON 节点)。 */
        public JsonNode excelColumns;
        /** 组件 SQL 视图(component_sql_view,组件内唯一,随组件走)。 */
        public List<SqlView> sqlViews;
    }

    public static class SqlView {
        public String sqlViewName;
        public String sqlTemplate;
        public JsonNode declaredColumns;
        public List<String> requiredVariables;
        public String scope;
        public String description;
    }

    public static class Dependencies {
        /** 引用到的全局变量 code(global_variable_code / GLOBAL_VARIABLE 绑定)。 */
        public List<String> globalVariables;
        /** 引用到的数据源 code(DATABASE_QUERY / HTTP_API 绑定)。 */
        public List<String> datasources;
    }

    /** task-0805 R1：整个目录的公式绑定完整性汇总（跨该目录所有导出组件）。 */
    public static class BindingReport {
        /** = items 中 status==UNRESOLVABLE 的条数。 */
        public int unboundCount;
        /** 扫描到的绑定点总数（普通 FORMULA 字段 + 条件公式内部引用）。 */
        public int totalFormulaRefs;
        public List<BindingReportItem> items;
    }

    /** task-0805：单条绑定检查结果（条件公式内部引用的 fieldName 写成「字段名 › 规则N」/「字段名 › 默认」）。 */
    public static class BindingReportItem {
        public String componentCode;
        public String componentName;
        public String fieldName;
        /** 解析不到为 null。 */
        public String resolvedFormulaId;
        public String resolvedFormulaName;
        /** BOUND | RESOLVED_BY_NAME | RESOLVED_BY_POSITION | UNRESOLVABLE */
        public String status;
        /** UNRESOLVABLE 时给人话原因，其余为 null。 */
        public String message;
    }
}
