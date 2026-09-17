package com.cpq.component.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 组件目录导出 bundle（P1,只读导出产物）。
 *
 * <p>导出某目录**直属**组件的完整配置 + 依赖清单。设计见 docs/PRD-v3.md §5.4.6。
 * tempId 不在导出端使用(P1 仅导出);导入端(P2/P3)按 code 做冲突处理 + 重映射。
 *
 * <h2>🚨 空值序列化口径（task-260915 B-9 / AC-19，改之前先读完）</h2>
 *
 * <p><b>1.1 新增的 8 个字段一律带 {@code @JsonInclude(NON_NULL)}，既有字段一律不带。</b>
 * 这不是风格问题，是 checksum 的正确性问题：
 *
 * <p>导入端 {@code ComponentImportService#verifyChecksum} 拿<b>反序列化后的本 DTO 对象</b>
 * 重新序列化再比对 —— 不是校验原始字节。⇒ 本类每加一个会被序列化成 {@code "xxx":null} 的字段，
 * <b>所有老 bundle 的 checksum 当场全部失配</b>（老包里根本没有这个键，重算出的字节多了一段）。
 * 实测：加完 8 个字段未加注解时，{@code Task0805ExportBindingReportTest} 的
 * {@code oldBundle_checksumStillValid} 由 2/18 失败恶化为 <b>18/18 失败</b>；加上注解后回到 2/18。
 *
 * <p>🚫 <b>反过来也不许</b>：给<b>既有</b>字段加 {@code NON_NULL} 同样会改变老包形状
 * （老包的 JSON 里 {@code "sortField":null} 是<b>写出来了</b>的），是同一个坑的镜像。
 *
 * <p>⚠️ <b>将来再加新字段时</b>：新字段也要带 {@code NON_NULL}，否则老包 checksum 再次全红。
 * 这条由 {@code ExportBundleFieldCoverageTest} 的
 * {@code newFieldsMustBeAnnotatedNonNull} 守门（漏一个即失败并点名）。
 */
public class ComponentExportBundle {

    /**
     * bundle 格式版本,导入端据此判断兼容性。
     *
     * <p><b>1.1（task-260915）</b>：补齐此前丢失的 8 个字段 —— 组件级 {@code treeConfig} /
     * {@code bomRecursiveExpand} / {@code elementCodeField} / {@code elementPriceField} /
     * {@code elementCurrencyField}，视图级 {@code builderConfig} / {@code builderVersion} /
     * {@code status}。导入端据此区分：{@code "1.1"} 走完整恢复；{@code "1.0"} 或缺失走降级
     * （新字段全 null，行为与 task-260915 之前逐字一致，<b>不整包拒绝</b>）。
     */
    public String bundleVersion = "1.1";
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
        /**
         * 树表展示配置(原 {@code component.tree_config} JSONB,{@code {idField,parentField,defaultExpanded}})。
         * 导入端须一并恢复,否则导入后组件数据自带的树结构塌回平铺(行集合不变、层级没了)。
         * 老 bundle(1.0,无此字段)反序列化后为 null,导入端按"源就是空"处理。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public JsonNode treeConfig;
        /**
         * 核价 BOM 递归展开开关(原 {@code component.bom_recursive_expand})。
         * 丢失后果:导入后的 BOM 页签只渲染根料号一行,不再按闭包递归展开子料号。
         * 1.0 老包无此字段=null,导入端回落 {@code tabType=='BOM'} 推导(见 ComponentImportService)。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Boolean bomRecursiveExpand;
        /**
         * 元素编码列(原 {@code component.element_code_field})。接元素价格策略的组件靠它匹配
         * {@code element_price_version_item.element_code}。丢失后果:该组件在目标库<b>保存期直接 400</b>
         * (COMPONENT_ELEMENT_BINDING_REQUIRED) —— 视图里有取价函数却没有元素列绑定。
         * 老 bundle 无此字段=null。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String elementCodeField;
        /**
         * 元素单价列(原 {@code component.element_price_field})。与 {@link #elementCodeField} 成对必填,
         * 丢失后果同上(保存期 400)。老 bundle 无此字段=null。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String elementPriceField;
        /**
         * 币种列(原 {@code component.element_currency_field},可空)。丢失后果:导入后元素单价的币种列
         * 不再回填,报价/核价上该列恒空。老 bundle 无此字段=null。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String elementCurrencyField;
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
        /**
         * 取数配置器(builder)的配置 JSONB(原 {@code component_sql_view.builder_config})。
         *
         * <p>🚨 <b>它是「这个页签是不是树」的唯一凭据</b>：配置器建的页签 {@code component.tab_type}
         * 天然为 NULL,树身份记在本字段的 {@code tabType} 里(见 {@code TabSemanticResolver} 分支①)。
         * 丢失后果有两层:① 导入后配置器打开是空的,只剩一段无法再编辑的死 SQL;
         * ② 树身份丢失 ⇒ 含 {@code tree_ref}/{@code tree_attr} 的公式在导入校验闸②被 400 拒绝
         * (task-260915 的起因报错)。
         *
         * <p>⚠️ <b>原样透传,不裁剪/不规范化/不重排键序</b> —— 二次往返须逐字段相等。
         * ⚠️ 本 JSON 顶层也有一个 {@code builderVersion} 键,与视图列 {@link #builderVersion}
         * 是<b>两个不同来源</b>,两个都要带,不要合并、不要假设相等。
         * 老 bundle(1.0)无此字段=null,导入端据此判定「该组件没有配置器信息」。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public JsonNode builderConfig;
        /**
         * 取数配置器的编译器版本(原 {@code component_sql_view.builder_version},integer)。
         * 与 {@link #builderConfig} <b>成对写入</b>:{@code TabSemanticResolver} 的分支① 判据正是
         * {@code builder_version IS NOT NULL},缺它则分支① 永不成立(即使 builder_config 有值)。
         * 老 bundle 无此字段=null。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Integer builderVersion;
        /**
         * 视图启用状态(原 {@code component_sql_view.status},ACTIVE / INACTIVE)。
         * 丢失后果:导入端此前硬编码 {@code "ACTIVE"},源库里已停用的视图会被<b>悄悄激活</b>。
         * 老 bundle 无此字段=null,导入端此时才回落默认 {@code "ACTIVE"}(与改动前一致)。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String status;
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
