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
}
