package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.formula.TokenMappabilityValidator;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.template.entity.Template;
import com.cpq.template.service.PublishedTemplateReader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComponentService {

    private static final Logger LOG = Logger.getLogger(ComponentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 用户可录入的字段类型集合（含可编辑字段的多行 driver 组件须声明 rowKeyFields）。
     * 对应 docs/组件管理字段配置指南.md §二 "用户输入" 类别。
     */
    private static final Set<String> EDITABLE_FIELD_TYPES =
        Set.of("INPUT_NUMBER", "INPUT_TEXT", "LIST_FORMULA");

    // task-0806 阶段⓪ D13：白名单 8 → 6，剔除 DATA_SOURCE / INPUT（禁新配，代码分支全部保留，实测三载体零使用）
    private static final Set<String> VALID_FIELD_TYPES = Set.of(
        "FIXED_VALUE", "INPUT_TEXT", "INPUT_NUMBER", "FORMULA",
        "BASIC_DATA",  // V5: BNF 路径绑定基础数据物理表(对应前端 PathPickerDrawer)
        "LIST_FORMULA" // V203/Phase B: 配置模板驱动 + IF-ELSE-IF 条件分支公式
    );

    public static final java.util.Set<String> VALID_COMPONENT_TYPES =
        java.util.Set.of("NORMAL", "SUBTOTAL", "EXCEL");

    /**
     * task-0721 B4：页签类型属性值域（5 类，需求说明 §4.3 规则一）。
     * BOM=树状页签(结构角色)；材质元素/零件/外购件=对应 characteristic 三态；主件=成品/树根。
     */
    public static final java.util.Set<String> VALID_TAB_TYPES =
        java.util.Set.of("BOM", "材质元素", "零件", "外购件", "主件");

    /** tabType 非法值 → 400；null/blank 视为未配置，放行。 */
    public static void assertValidTabType(String tabType) {
        if (tabType == null || tabType.isBlank()) return;
        if (!VALID_TAB_TYPES.contains(tabType)) {
            throw new BusinessException(400, "Invalid tabType: " + tabType +
                ". Must be one of: " + VALID_TAB_TYPES);
        }
    }

    /**
     * task-0721（2026-07-21 补录，2026-07-23 放宽）：这 4 类 tabType 是"物料语义"页签，类型判定
     * （{@code BomNodeTypeResolver}）与加叶子候选料号采集依赖其"标识列"取值，故保存期强制要求
     * {@code partNoField} 或 {@code partNameField} 至少配一个。{@code BOM}（树页签）料件标识取系统列
     * {@code __hfPartNo}，不在此列。
     *
     * <p>**2026-07-23 修订背景**：部分页签（如「外购件/费用」类）没有料号列，只用「料件名称」
     * （如「组成件1」）做标识，此前"必须配 part_no_field"过严会把这类页签堵死。
     */
    private static final java.util.Set<String> TAB_TYPES_REQUIRE_PART_NO_FIELD =
        java.util.Set.of("材质元素", "零件", "外购件", "主件");

    /**
     * task-0721 B4：页签类型属性写入编排。{@code requestedTabType == null} → tabType 本身不变（既有
     * {@code bomRecursiveExpand} 手动设置保留），但仍按【当前生效的 tabType + 本次合并后的
     * partNoField/partNameField】校验标识列要求（见 {@link #assertPartNoFieldRequirement}）。
     * {@code requestedTabType} 非 null 时：① 值域校验；② {@code tabType="BOM"} 时先跑 COSTING 模板
     * 反向护栏（见 {@link #assertNotReferencedByCostingTemplate}）；③ 与 {@code bomRecursiveExpand}
     * 自动同步（2026-07-21 裁决 Q1："BOM"→true，其他值→false；仅是兼容既有 UI/查询的实现细节，
     * <b>不参与</b>报价侧树渲染路由判断——路由判据是 {@link BomTreeRenderService#isQuoteTreeTabType}）。
     *
     * @param requestedPartNoField   非 null → 覆盖 {@code component.partNoField}（空串=清空）
     * @param requestedPartNameField 非 null → 覆盖 {@code component.partNameField}（空串=清空）
     */
    // task-0803 Task5：从 private 改为 package-private，供纯 JUnit 单测直接构造 Component
    // （不落库）验证闸③，避免 @QuarkusTest + DB 才能测到这条护栏。
    void applyTabType(Component component, String requestedTabType,
                              String requestedPartNoField, String requestedPartNameField) {
        if (requestedPartNoField != null) {
            component.partNoField = requestedPartNoField.isBlank() ? null : requestedPartNoField;
        }
        if (requestedPartNameField != null) {
            component.partNameField = requestedPartNameField.isBlank() ? null : requestedPartNameField;
        }

        if (requestedTabType != null) {
            assertValidTabType(requestedTabType);
            String normalized = requestedTabType.isBlank() ? null : requestedTabType;
            // task-0803 Task5 闸③（反向闸，需求 §4.3.8）：记录"变更前"是否为 BOM，
            // 必须在 component.tabType 被下面覆盖之前取值。
            boolean wasBom = "BOM".equals(component.tabType);
            if (BomTreeRenderService.isQuoteTreeTabType(normalized)) {
                assertNotReferencedByCostingTemplate(component.id);
                component.bomRecursiveExpand = Boolean.TRUE;
            } else {
                // 组件此前是 BOM 树页签、公式里已经用了父子取值（tree_ref/tree_attr），
                // 却要把 tabType 改离 BOM → 拒绝。只在"真发生转出"时拦，不影响
                // 新建组件（wasBom 天然 false）或本就非 BOM 的组件（不该出现父子 token，
                // 但即便脏数据存在也不在此闸拦——那是闸②的职责）。
                if (wasBom && formulasContainTreeToken(component.formulas)) {
                    throw new BusinessException(400,
                        "该组件公式中已使用父子取值（tree_ref/tree_attr），不能将 tabType 从 BOM 改为「" +
                        (normalized == null ? "(空)" : normalized) + "」。请先删除公式中的父子取值引用，再修改 tabType。");
                }
                // task-0803（2026-08-04）：条件公式的 when 里用树属性同样要拦 —— 与闸②-b 对称。
                // 漏了这条会出现「正着存不进去、但先建成 BOM 再改类型就能留下」的绕过路径。
                if (wasBom && fieldsContainCondTreeAttr(component.fields)) {
                    throw new BusinessException(400,
                        "该组件已有字段的条件公式使用了树属性（[层级]/[是否叶子]/[是否根]），不能将 tabType 从 BOM 改为「" +
                        (normalized == null ? "(空)" : normalized) + "」。请先删除条件中的树属性引用，再修改 tabType。");
                }
                component.bomRecursiveExpand = Boolean.FALSE;
            }
            component.tabType = normalized;
        }

        assertPartNoFieldRequirement(component.tabType, component.partNoField, component.partNameField);
    }

    /**
     * task-0803 Task5：判断 formulas JSON 中是否含至少一个 {@code tree_ref}/{@code tree_attr} token
     * （递归扫描，见 {@link #walkTokensDeep}——嵌套在 {@code cross_tab_ref.targetExpr} 等任意
     * 子表达式容器内的父子 token 也算数，不只看 expression 数组顶层）。用于闸③"是否需要拦"的判断。
     */
    private boolean formulasContainTreeToken(String formulasJson) {
        for (Map<String, Object> formula : parseList(formulasJson)) {
            Object exprObj = formula.get("expression");
            if (!(exprObj instanceof List<?> exprList)) continue;
            boolean[] found = {false};
            walkTokensDeep(exprList, (type, token) -> {
                if ("tree_ref".equals(type) || "tree_attr".equals(type)) found[0] = true;
            });
            if (found[0]) return true;
        }
        return false;
    }

    /**
     * task-0803 Task5：BOM 父子取值（{@code tree_ref}/{@code tree_attr}）+ {@code previous_row_subtotal}
     * 的组件级校验闸门，保存期强制执行（需求 §4.3.7/§4.3.8）。三道闸：
     * <ul>
     *   <li>闸①：每个 {@code tree_ref} token 的 {@code targetExpr} 内层白名单
     *       （委托 {@link TokenMappabilityValidator#validateTreeRefTargetExpr}），
     *       不论该 {@code tree_ref} 出现在公式顶层还是嵌套在别的 token（如
     *       {@code cross_tab_ref.targetExpr}）内部都会触发。</li>
     *   <li>闸②（正向闸）：公式【任意嵌套深度】含 {@code tree_ref}/{@code tree_attr}，但组件
     *       {@code tabType != "BOM"} → 拒绝，错误信息点名具体公式。</li>
     *   <li>闸④：{@code tabType == "BOM"} 的组件公式【任意嵌套深度】含
     *       {@code previous_row_subtotal} → 拒绝（树上"上一行"是展开后的数组顺序，可能是
     *       父/兄弟/叔叔的孙子，语义模糊，配了必是误用）。{@code hasPrev && isBom} 这个判据本身
     *       同时覆盖"组件已是 BOM、新配 PREV 公式"与"组件已有 PREV 公式、把 tabType 改入 BOM"
     *       两个方向——两者最终都落到"本次保存后 tabType=BOM 且 formulas 含 PREV"这一个状态，
     *       判据没有方向性，不要在重构时拆成两条各查一半的分支。</li>
     * </ul>
     *
     * <p><b>2026-08-03 评审返修</b>：原实现只平铺遍历 expression 数组顶层 token，把
     * {@code tree_ref}/{@code tree_attr} 嵌套写进 {@code cross_tab_ref.targetExpr}（或任何带
     * 子表达式的 token 内部）即可绕过闸②/闸④。改为 {@link #walkTokensDeep} 通用递归扫描——
     * 不针对某个具体键名，任何值为"token 数组"的字段都继续下钻，堵死这类嵌套绕过，也不怕
     * 以后新增别的带子表达式键名的 token 类型时又漏。
     *
     * <p>package-private：供 ComponentImportService（同包）在导入 bundle 时复用（⑤），
     * 也供纯 JUnit 单测直接调用（不落库）。
     *
     * @param tabType      组件【本次保存后生效】的最终 tabType（null/非 "BOM" 均按"非 BOM"处理）
     * @param formulasJson 组件【本次保存后生效】的最终 formulas JSON 字符串
     */
    /** 组件任一字段的条件公式是否引用了树属性保留字（闸③反向闸用）。 */
    private boolean fieldsContainCondTreeAttr(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) return false;
        for (Map<String, Object> field : parseList(fieldsJson)) {
            Object cfObj = field.get("conditional_formula");
            if (cfObj == null) cfObj = field.get("conditionalFormula");
            if (!(cfObj instanceof Map<?, ?> cf)) continue;
            if (!(cf.get("rules") instanceof List<?> rules)) continue;
            for (Object rObj : rules) {
                if (rObj instanceof Map<?, ?> rule && condTreeUsesTreeAttrRaw(rule.get("when"))) return true;
            }
        }
        return false;
    }

    /** 树属性保留字。与前端 condTree.TREE_ATTR_COLS / FormulaCalculator.TREE_ATTR_COLS 三处逐字同步。 */
    private static final java.util.Set<String> TREE_ATTR_COLS =
        java.util.Set.of("层级", "是否叶子", "是否根");

    /** 递归判断一棵原始 Map 形态的 CondTree 是否引用树属性保留字（leaf.left 与 column 型 rhs 都算）。 */
    @SuppressWarnings("unchecked")
    private static boolean condTreeUsesTreeAttrRaw(Object when) {
        if (!(when instanceof Map<?, ?> node)) return false;
        Object kind = node.get("kind");
        if ("group".equals(kind)) {
            Object children = node.get("children");
            if (children instanceof List<?> list) {
                for (Object c : list) if (condTreeUsesTreeAttrRaw(c)) return true;
            }
            return false;
        }
        Object left = node.get("left");
        if (left != null && TREE_ATTR_COLS.contains(left.toString())) return true;
        Object rhs = node.get("rhs");
        if (rhs instanceof Map<?, ?> r && "column".equals(r.get("type"))) {
            Object v = r.get("value");
            return v != null && TREE_ATTR_COLS.contains(v.toString());
        }
        return false;
    }

    /**
     * 兼容重载：不校验条件公式（老调用点 / 单测用）。新代码请用三参版。
     */
    void assertTreeTokenGates(String tabType, String formulasJson) {
        assertTreeTokenGates(tabType, formulasJson, null);
    }

    /**
     * @param fieldsJson 组件字段 JSON。task-0803（2026-08-04）：条件公式的 {@code when} 里也能用
     *        树属性保留字（[层级]/[是否叶子]/[是否根]），而 {@code conditional_formula} 挂在
     *        <b>fields</b> 上、不在 formulas 里 —— 只扫 formulas 会让非 BOM 页签把
     *        「按 [是否叶子] 分流」的条件存进库，绕过闸②。传 null 表示跳过该项校验（兼容重载）。
     */
    void assertTreeTokenGates(String tabType, String formulasJson, String fieldsJson) {
        List<Map<String, Object>> formulas = parseList(formulasJson);
        boolean isBom = "BOM".equals(tabType);
        TokenMappabilityValidator innerValidator = new TokenMappabilityValidator();

        for (Map<String, Object> formula : formulas) {
            Object nameObj = formula.get("name");
            String formulaName = nameObj == null || nameObj.toString().isBlank()
                ? "(未命名)" : nameObj.toString();
            Object exprObj = formula.get("expression");
            if (!(exprObj instanceof List<?> exprList)) continue;

            boolean[] hasTreeToken = {false};
            boolean[] hasPrev = {false};
            walkTokensDeep(exprList, (type, token) -> {
                if ("tree_ref".equals(type)) {
                    hasTreeToken[0] = true;
                    // 闸①：targetExpr 内层白名单（无论该 tree_ref 出现在哪一层嵌套、
                    // 也无论 tabType 是否为 BOM，结构非法都要拒绝）
                    List<Map<String, Object>> targetExpr = asTokenList(token.get("targetExpr"));
                    TokenMappabilityValidator.Result r = innerValidator.validateTreeRefTargetExpr(targetExpr);
                    if (!r.mappable()) {
                        throw new BusinessException(400,
                            "公式「" + formulaName + "」的父子取值（tree_ref）非法：" + r.reason());
                    }
                } else if ("tree_attr".equals(type)) {
                    hasTreeToken[0] = true;
                } else if ("previous_row_subtotal".equals(type)) {
                    hasPrev[0] = true;
                }
            });

            // 闸②：正向闸 —— 用了父子 token（含任意嵌套深度）但组件不是 BOM 树页签 → 拒绝，点名公式
            if (hasTreeToken[0] && !isBom) {
                throw new BusinessException(400,
                    "公式「" + formulaName + "」使用了父子取值（tree_ref/tree_attr），" +
                    "该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType=" +
                    (tabType == null || tabType.isBlank() ? "(未配置)" : tabType) + "。");
            }
            // 闸④：BOM 页签禁用 previous_row_subtotal（需求 §4.3.7，树上"上一行"语义模糊）
            if (hasPrev[0] && isBom) {
                throw new BusinessException(400,
                    "公式「" + formulaName + "」使用了 previous_row_subtotal（上一行取值），" +
                    "BOM 树页签禁止使用该 token（树展开后的「上一行」可能是父/兄弟/叔叔的孙子，" +
                    "语义模糊；如需父子间取值请改用 tree_ref）。");
            }
        }

        // ── 闸②-b（task-0803 2026-08-04）：条件公式 when 里的树属性保留字 ──
        // conditional_formula 在 fields 上，上面那轮只扫了 formulas.expression，扫不到条件。
        if (fieldsJson != null && !isBom) {
            for (Map<String, Object> field : parseList(fieldsJson)) {
                Object cfObj = field.get("conditional_formula");
                if (cfObj == null) cfObj = field.get("conditionalFormula");
                if (!(cfObj instanceof Map<?, ?> cf)) continue;
                Object rulesObj = cf.get("rules");
                if (!(rulesObj instanceof List<?> rules)) continue;
                for (Object rObj : rules) {
                    if (!(rObj instanceof Map<?, ?> rule)) continue;
                    if (!condTreeUsesTreeAttrRaw(rule.get("when"))) continue;
                    Object fn = field.get("name");
                    throw new BusinessException(400,
                        "字段「" + (fn == null || fn.toString().isBlank() ? "(未命名)" : fn)
                        + "」的条件公式使用了树属性（[层级]/[是否叶子]/[是否根]），"
                        + "该功能仅支持 tabType=\"BOM\" 的树页签组件，当前组件 tabType="
                        + (tabType == null || tabType.isBlank() ? "(未配置)" : tabType) + "。");
                }
            }
        }
    }

    /**
     * task-0803 Task5 返修（2026-08-03 评审）：通用递归 token 访问器。
     *
     * <p>对 {@code tokenList} 中每个 token 调一次 {@code visitor.accept(type, token)}；
     * 然后不论该 token 是什么类型，只要它的某个字段值满足"token 数组"形状
     * （{@link #isTokenArrayValue}：非空 List 且元素全部是含 {@code type} 键的 Map），
     * 就继续递归下钻——不针对 {@code targetExpr} 这个具体键名硬编码，覆盖
     * {@code cross_tab_ref.targetExpr}/{@code tree_ref.targetExpr} 以及未来任何新增的
     * 带子表达式键名的 token 类型。{@code match}/{@code predicate}/{@code sources} 等
     * 元素不含 {@code type} 键的结构不会被误判为 token 数组，不会被下钻。
     *
     * <p>{@code visitor} 可以抛未受检异常（如 {@link BusinessException}）来中止扫描，
     * 异常会正常沿调用栈向上传播。
     */
    private static void walkTokensDeep(List<?> tokenList,
                                        java.util.function.BiConsumer<String, Map<String, Object>> visitor) {
        if (tokenList == null) return;
        for (Object opObj : tokenList) {
            if (!(opObj instanceof Map<?, ?> rawToken)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> token = (Map<String, Object>) rawToken;
            Object typeObj = token.get("type");
            String type = typeObj == null ? "" : typeObj.toString();
            visitor.accept(type, token);
            for (Object v : token.values()) {
                if (isTokenArrayValue(v)) {
                    walkTokensDeep((List<?>) v, visitor);
                }
            }
        }
    }

    /**
     * 判断一个字段值是否是"token 数组"：非空 {@code List}，且每个元素都是含
     * {@code type} 键的 {@code Map}（{@code match}/{@code sources} 等元素形状不同的
     * 数组字段不满足此判据，天然被排除，不会被误当成子表达式下钻）。
     */
    private static boolean isTokenArrayValue(Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !m.containsKey("type")) return false;
        }
        return true;
    }

    /** JSON 反序列化后的 targetExpr Object → List&lt;Map&lt;String,Object&gt;&gt;；非 List 视为空。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asTokenList(Object obj) {
        if (!(obj instanceof List<?> list)) return java.util.List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    /**
     * task-0721（2026-07-21 补录，2026-07-23 放宽为"料号列或名称列至少一个"）：
     * {@code tabType ∈ {材质元素,零件,外购件,主件}} 但 {@code partNoField}/{@code partNameField}
     * 均缺 → 400（api.md §1 / 需求说明 §4.3 规则一 2026-07-23 修订）。校验对象是【本次保存后生效的
     * 最终状态】，而非仅本次请求携带的字段——即便本次请求只改了别的字段、未碰 tabType/两个标识列，
     * 只要合并后仍处于"要求标识列但两者皆缺"的非法状态就拦，不放过存量脏数据继续演化。
     */
    private static void assertPartNoFieldRequirement(String tabType, String partNoField, String partNameField) {
        if (tabType == null || !TAB_TYPES_REQUIRE_PART_NO_FIELD.contains(tabType)) return;
        boolean noField = partNoField == null || partNoField.isBlank();
        boolean noNameField = partNameField == null || partNameField.isBlank();
        if (noField && noNameField) {
            throw new BusinessException(400,
                "tabType=" + tabType + " 类型页签必须配置料号列或名称列至少一个作为匹配标识，否则该页签无法参与类型判定匹配");
        }
    }

    /**
     * task-0729 B7（§11.15.3.2）：该组件任一 {@code component_sql_view.sql_template} 检测到取价
     * 函数（{@code f_customer_element_price}/{@code f_material_element_price}）→
     * {@code elementCodeField}+{@code elementPriceField} 必填，否则 400
     * {@code COMPONENT_ELEMENT_BINDING_REQUIRED}（不是警告、不是静默保存）。
     * 未接取价函数的组件三项留空可正常保存（验收 #32③）。
     */
    private void assertElementBindingRequirement(Component component) {
        if (component.id == null) return; // 新建流程尚无 id，不存在既有 sql_views，天然放行
        List<ComponentSqlView> sqlViews = sqlViewRepository.listByComponent(component.id);
        boolean referencesElementPriceFunction = sqlViews.stream()
            .anyMatch(v -> ElementBindingDerivation.referencesElementPriceFunction(v.sqlTemplate));
        if (!referencesElementPriceFunction) return;

        List<String> missing = new ArrayList<>();
        if (component.elementCodeField == null || component.elementCodeField.isBlank()) missing.add("elementCodeField");
        if (component.elementPriceField == null || component.elementPriceField.isBlank()) missing.add("elementPriceField");
        if (!missing.isEmpty()) {
            throw new com.cpq.component.exception.ComponentElementBindingRequiredException(missing);
        }
    }

    /**
     * task-0721 B4 强制护栏（2026-07-21 业务方裁决）：{@code bomRecursiveExpand} 是组件级全局开关，
     * 同一组件被多模板共用时一开全生效。现网实查：3 个开启该开关的组件
     * （COMP-0021__imp1__imp1 / COMP-0039 / COMP-0042）共 34 处模板引用，<b>全部在 COSTING 模板</b>。
     *
     * <p><b>⚠️ 2026-08-14 repair-0814 收窄（重要，别改回去）</b>：原判据是「被<b>任一</b> COSTING 模板引用即拒绝」，
     * 不过滤 {@code template.status}、不看是否已冻结。该判断在 task-0721（2026-07-21）成立——当时已发布模板
     * 的渲染配置实时读活表 {@code component}。但 task-0806「模板发布全量冻结」（{@code 8d04336a}）把
     * {@code tab_type} / {@code bom_recursive_expand} 双双冻进 {@code template_component_snapshot}，
     * 读取收口到 {@link PublishedTemplateReader}，{@code refreshSnapshotsByComponent}（H1）整体退役
     * ——<b>前提消失，老判据退化为纯误拦</b>（当时实测：61 条 COSTING 引用全已冻结、22 个组件被无谓锁死）。
     *
     * <p>现判据：<b>只有【尚未冻结】的 COSTING 引用才拦</b>，即
     * {@code status ∉ {PUBLISHED, ARCHIVED}}（DRAFT，渲染期直读活表）
     * 或 {@code status ∈ {PUBLISHED, ARCHIVED}} 但快照零行（D17 未冻结过渡态）。
     * 判定委托 {@link PublishedTemplateReader#unfrozenAmong}，<b>不得</b>在此另写一份「什么叫已冻结」（AP-52）。
     *
     * <p>已冻结的核价模板放行，是因为改活表组件影响不到它们；下次<b>重新发布</b>时新配置才会生效，
     * 而那一刻由 {@code TemplateService.publish()} 的树页签不变量断言把关（同期加入，见 repair-0814 D-2）。
     */
    @SuppressWarnings("unchecked")
    private void assertNotReferencedByCostingTemplate(UUID componentId) {
        if (componentId == null) return; // 新建流程尚无 id，不存在既有模板引用，护栏天然不触发

        // ① 该组件的全部 COSTING 引用（SQL #1）。故意不在 SQL 里判冻结——「什么叫已冻结」
        //    的唯一定义在 PublishedTemplateReader，散第二份必漂移（AP-52）。
        List<UUID> costingTemplateIds = ((List<Object>) em.createNativeQuery(
                "SELECT DISTINCT tc.template_id FROM template_component tc " +
                "JOIN template t ON t.id = tc.template_id " +
                "WHERE tc.component_id = :cid AND t.template_kind = 'COSTING'")
            .setParameter("cid", componentId)
            .getResultList()).stream()
            .filter(Objects::nonNull)
            .map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString()))
            .collect(Collectors.toList());
        if (costingTemplateIds.isEmpty()) return;

        // ② 只有【尚未冻结】的引用才算数（SQL #2、#3，与引用数 N 无关）。
        List<Template> blocking = publishedTemplateReader.unfrozenAmong(costingTemplateIds);
        if (blocking.isEmpty()) return;

        // ③ 文案：点名具体模板 + 状态，且【多行】——前端 ComponentManagement#showSaveError 按
        //    是否含 '\n' 分流，多行走常驻 notification（duration:0 + pre-wrap），单行走 3s toast。
        //    这条提示要求用户去处理具体模板，必须常驻可读。见 api.md A-1。
        StringBuilder sb = new StringBuilder();
        sb.append("该组件被以下尚未冻结的核价(COSTING)模板引用，不能设为 BOM 树页签：");
        for (Template t : blocking) {
            sb.append("\n  · ").append(t.name)
              .append(t.version == null || t.version.isBlank() ? "" : " " + t.version)
              .append("（").append(t.status).append("）");
        }
        sb.append("\n这些模板渲染时直接读取组件活配置，改为树页签会立即改变它们的渲染方式。");
        sb.append("\n（已发布并已冻结的核价模板不受影响，故不在此列。）");
        throw new BusinessException(400, sb.toString());
    }

    /**
     * C1/C3 共用: default_source.path 视图路径解析正则。
     *
     * <p>匹配两种形态：
     * <ul>
     *   <li>group 1-3: {@code $$compCode.viewName.col}（GLOBAL 跨组件视图）</li>
     *   <li>group 4-5: {@code $viewName[pred].col} 或 {@code $viewName.col}（COMPONENT 视图）</li>
     * </ul>
     */
    static final Pattern VIEW_PATH_PATTERN = Pattern.compile(
        "^\\$\\$([^.$\\[.]+)\\.([^.$\\[.]+)\\.([^.$\\[.]+)" +  // group1=compCode group2=viewName group3=col
        "|" +
        "^\\$([^.$\\[.]+)(?:\\[[^\\]]*\\])?\\.([^.$\\[.]+)$"    // group4=viewName group5=col
    );

    public static void assertValidComponentType(String type) {
        String t = type == null ? "NORMAL" : type;
        if (!VALID_COMPONENT_TYPES.contains(t)) {
            throw new BusinessException("Invalid component_type: " + t +
                ". Must be one of: " + VALID_COMPONENT_TYPES);
        }
    }

    @Inject
    EntityManager em;

    // task-0806 B6：TemplateService 注入随 refreshSnapshotsByComponent（H1）整体退役一并移除
    // ——ComponentService 不再触碰任何模板 snapshot。

    // repair-0814：只读地【问】某批模板是否已冻结，用于 tabType=BOM 护栏的判定收窄。
    // 仍不写、不刷新任何 snapshot，B6 的边界未被破坏。
    @Inject
    PublishedTemplateReader publishedTemplateReader;

    @Inject
    ComponentSqlViewRepository sqlViewRepository;

    public List<ComponentDTO> list(UUID directoryId, String keyword) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (directoryId != null) {
            query.append(" AND directoryId = :directoryId");
            params.put("directoryId", directoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (name LIKE :kw OR code LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }
        return Component.<Component>list(query + " ORDER BY createdAt ASC", params)
            .stream()
            .map(ComponentDTO::from)
            .collect(Collectors.toList());
    }

    public ComponentDTO getById(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        return ComponentDTO.from(component);
    }

    /**
     * task-0729 B7（api.md §5.2）：迁移期/新建期推导预填，供屏 8 下拉给出推荐值。
     * 逐个 sql_view 尝试，取第一个成功捕获取价函数别名的结果；🔒 推导失败返回空壳，不报错
     * （§11.15.3.4 五步算法，运行期仅用于本推荐端点 + 迁移期批量预填，不用于报价渲染）。
     */
    public com.cpq.component.dto.ElementBindingSuggestDTO suggestElementBinding(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        com.cpq.component.dto.ElementBindingSuggestDTO dto = new com.cpq.component.dto.ElementBindingSuggestDTO();
        JsonNode fields;
        try {
            fields = MAPPER.readTree(component.fields);
        } catch (Exception e) {
            return dto; // fields 解析失败：空壳返回，不报错
        }

        List<ComponentSqlView> sqlViews = sqlViewRepository.listByComponent(id);
        for (ComponentSqlView v : sqlViews) {
            ElementBindingDerivation.Result r = ElementBindingDerivation.derive(v.sqlTemplate, v.sqlViewName, fields);
            if (r.alias == null) continue; // 该视图未接取价函数，试下一个
            dto.suggested.elementCodeField = r.elementCodeField;
            dto.suggested.elementPriceField = r.elementPriceField;
            dto.suggested.elementCurrencyField = r.elementCurrencyField;
            dto.alias = r.alias;
            dto.confidence = r.confidence;
            dto.warnings = r.warnings;
            return dto; // 命中即返回，不再继续找下一个视图
        }
        return dto; // 全部视图都未接取价函数：空壳返回
    }

    @Transactional
    public ComponentDTO create(CreateComponentRequest request) {
        validateRequest(request);

        String fieldsJson = toJson(request.fields);

        List<Map<String, Object>> fieldList = parseList(fieldsJson);
        List<Map<String, Object>> formulaList = parseList(toJson(request.formulas));

        // BL-0098 第一段（必须在 validateFormulas 之前）：补 id + 用 id 反查刷新名字冗余。
        // 用户在 UI 改公式名时，引用处的 formula_name / 条件公式 rules[].formula / default
        // 不会跟着变；不先刷新，validateFormulas 会以「绑定的公式 'X' 不存在」把保存挡下，
        // 「绑 id 后改名不断链」就只在数据层成立、UI 上根本改不了名。
        FormulaIdBinder.ensureFormulaIds(formulaList);
        FormulaIdBinder.refreshNameRedundancyFromIds(fieldList, formulaList);

        validateFields(fieldList);
        // repair-0803：传组件名 → 循环引用链路可显示「组件「X」内」；
        // 原 detectFormulaCircularReferences 已删（D-9：零定位信息，能力被
        // describeFormulaCyclesStructured + cyclicFormulaNodes 完全覆盖）。
        validateFormulas(fieldList, formulaList, request.name);  // may auto-correct formula names in-place

        // BL-0098 第二段：固化绑定 + 强制显式绑定。
        // 再刷一次名字冗余 —— validateFormulas 会就地改公式名，前一次刷新可能已陈旧（幂等，代价可忽略）。
        FormulaIdBinder.bindFormulaIdsToFields(fieldList, formulaList);
        FormulaIdBinder.refreshNameRedundancyFromIds(fieldList, formulaList);
        // 固化后仍未绑定 → 拒绝保存，杜绝新增隐式配置（IllegalArgumentException 由
        // GlobalExceptionMapper.handleIllegalArgument 映射成 400）。
        FormulaIdBinder.validateExplicitBinding(fieldList);

        // Re-serialize after auto-correction + id binding
        String formulasJson = toJson(formulaList);
        fieldsJson = toJson(fieldList);   // 固化出的 formula_id 必须回到 fieldsJson，否则不落库

        // Auto-generate code if not provided
        String code;
        if (request.code != null && !request.code.isBlank()) {
            code = request.code.trim();
            long count = Component.count("code", code);
            if (count > 0) {
                throw new BusinessException("Component code already exists: " + code);
            }
        } else {
            Long seq = (Long) em.createNativeQuery("SELECT nextval('component_code_seq')").getSingleResult();
            code = String.format("COMP-%04d", seq);
        }

        Component component = new Component();
        component.name = request.name.trim();
        component.code = code;
        component.directoryId = request.directoryId;
        component.fields = fieldsJson;
        component.formulas = formulasJson;
        component.columnCount = fieldList.size();
        component.componentType = request.componentType != null ? request.componentType : "NORMAL";
        component.excelColumns = request.excelColumns != null ? request.excelColumns : "[]";
        component.dataDriverPath = normalizeDriverPath(request.dataDriverPath);
        component.status = request.status != null ? request.status : "ACTIVE";

        // rowKeyFields：直接透传 JSON 字符串（前端传 List，序列化为 JSON；null=未配置）
        if (request.rowKeyFields != null) {
            component.rowKeyFields = toJsonRaw(request.rowKeyFields);
        }

        // 树表配置:校验后存 JSON(null=非树表)
        validateTreeConfig(request.treeConfig, request.fields);
        component.treeConfig = request.treeConfig != null ? toJsonRaw(request.treeConfig) : null;
        // 核价 BOM 递归展开开关(默认 false:勾选才递归)
        component.bomRecursiveExpand = request.bomRecursiveExpand != null ? request.bomRecursiveExpand : Boolean.FALSE;
        // task-0721 B4：页签类型属性(校验 + COSTING 模板反向护栏 + 与 bomRecursiveExpand 自动同步；
        // 传值时覆盖上一行手动设置的 bomRecursiveExpand)。新建流程尚无 id,反向护栏天然不触发。
        // task-0721（补录）：一并写入 partNoField/partNameField + 校验"限定 tabType 必须配 partNoField"。
        applyTabType(component, request.tabType, request.partNoField, request.partNameField);
        // task-0803 Task5 闸①②④：父子取值(tree_ref/tree_attr) + previous_row_subtotal 的
        // tabType 联动校验，必须在 applyTabType 之后跑(此时 component.tabType 已是最终生效值)。
        assertTreeTokenGates(component.tabType, component.formulas, component.fields);
        // task-0722：行排序列(可空)。非 null 时覆盖(空串=清空)。
        if (request.sortField != null) component.sortField = request.sortField.isBlank() ? null : request.sortField;

        // task-0729 B7：元素编码列/元素单价列/货币列，与 partNoField 等平级（新建路径：无 sql_views
        // 可查，requireElementBinding 天然 no-op；仍统一走一遍校验，保持新建/更新同一套逻辑）。
        if (request.elementCodeField != null) component.elementCodeField = request.elementCodeField.isBlank() ? null : request.elementCodeField;
        if (request.elementPriceField != null) component.elementPriceField = request.elementPriceField.isBlank() ? null : request.elementPriceField;
        if (request.elementCurrencyField != null) component.elementCurrencyField = request.elementCurrencyField.isBlank() ? null : request.elementCurrencyField;

        // 行键校验（新建路径：硬拦）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, true);

        component.persist();
        assertElementBindingRequirement(component);

        // C3: 保存后对 default_source.path 列名做软校验（只 warn，不阻断）
        warnDefaultSourcePaths(component.id, fieldList);

        LOG.infof("Created component id=%s code=%s", component.id, component.code);
        return ComponentDTO.from(component);
    }

    @Transactional
    public ComponentDTO update(UUID id, CreateComponentRequest request) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }

        if (request.code != null && !request.code.equals(component.code)) {
            long count = Component.count("code", request.code);
            if (count > 0) {
                throw new BusinessException("Component code already exists: " + request.code);
            }
            component.code = request.code.trim();
        }

        if (request.name != null && !request.name.isBlank()) {
            component.name = request.name.trim();
        }
        if (request.directoryId != null) {
            component.directoryId = request.directoryId;
        }
        if (request.componentType != null) {
            component.componentType = request.componentType;
        }
        if (request.excelColumns != null) {
            component.excelColumns = request.excelColumns;
        }
        // dataDriverPath 单独按"显式空字符串=清空"处理:null 保持不变,空串=NULL 化
        // [Y1.5 DEBUG] 记录入参,排查保存丢失
        LOG.infof("[Y1.5 component update] id=%s code=%s incoming dataDriverPath='%s' (null=%s)",
                id, component.code, request.dataDriverPath, request.dataDriverPath == null);
        if (request.dataDriverPath != null) {
            component.dataDriverPath = normalizeDriverPath(request.dataDriverPath);
            LOG.infof("[Y1.5 component update] saved dataDriverPath='%s'", component.dataDriverPath);
        }
        if (request.status != null) {
            component.status = request.status;
        }

        if (request.fields != null || request.formulas != null) {
            String fieldsJson = request.fields != null ? toJson(request.fields) : component.fields;

            List<Map<String, Object>> fieldList = parseList(fieldsJson);
            List<Map<String, Object>> formulaList = parseList(
                request.formulas != null ? toJson(request.formulas) : component.formulas
            );

            // BL-0098 第一段（同 create，须先于 validateFormulas）：补 id + 刷新名字冗余。
            FormulaIdBinder.ensureFormulaIds(formulaList);
            FormulaIdBinder.refreshNameRedundancyFromIds(fieldList, formulaList);

            validateFields(fieldList);
            // repair-0803：同 create —— 传组件名 + 移除 detectFormulaCircularReferences（D-9）
            validateFormulas(fieldList, formulaList, component.name);  // may auto-correct formula names in-place

            // BL-0098：公式补稳定 id → 字段绑定固化成 formula_id。
            // ⚠️ update 直接用前端送来的 formulas 覆盖库里的值。若前端没把 id 带回来，这里会给
            //    每条公式重新生成新 id，导致存量 formula_id 全部失配（那一列静默不出值）。
            //    前端已实测会自动往返 id：ComponentManagement 加载时 {...f, key} 保留全部键、
            //    保存时 ({key: _k, ...rest}) 只剥 key、componentDraft.stripFieldKeys 同理。
            //    Task 8 的前端测试锁死这一点，改前端映射时务必同步复查。
            FormulaIdBinder.bindFormulaIdsToFields(fieldList, formulaList);
            FormulaIdBinder.refreshNameRedundancyFromIds(fieldList, formulaList);
            FormulaIdBinder.validateExplicitBinding(fieldList);

            // Re-serialize after auto-correction + id binding
            component.fields = toJson(fieldList);   // 用固化后的 fieldList，不是原 fieldsJson
            component.formulas = toJson(formulaList);
            component.columnCount = fieldList.size();

            // C3: default_source.path 列名软校验（只 warn，不阻断）
            warnDefaultSourcePaths(id, fieldList);
        }

        // rowKeyFields 更新（null=不变，传值=覆盖）
        if (request.rowKeyFields != null) {
            component.rowKeyFields = toJsonRaw(request.rowKeyFields);
        }

        // 树表配置更新(null=不变;传值=覆盖,空对象/缺字段=清空)
        if (request.treeConfig != null) {
            validateTreeConfig(request.treeConfig, component.fields);
            boolean hasBoth = request.treeConfig.get("idField") != null
                    && request.treeConfig.get("parentField") != null;
            component.treeConfig = hasBoth ? toJsonRaw(request.treeConfig) : null;
        }
        // 核价 BOM 递归展开开关更新(null=不变)
        if (request.bomRecursiveExpand != null) {
            component.bomRecursiveExpand = request.bomRecursiveExpand;
        }
        // task-0721 B4：页签类型属性(校验 + COSTING 模板反向护栏 + 与 bomRecursiveExpand 自动同步；
        // 传值时覆盖上面手动设置的 bomRecursiveExpand)。
        // task-0721（补录）：一并写入 partNoField/partNameField + 校验"限定 tabType 必须配 partNoField"
        // ——校验对象是合并后的最终状态,即便本次只改 partNoField 不改 tabType 也会校验。
        applyTabType(component, request.tabType, request.partNoField, request.partNameField);
        // task-0803 Task5 闸①②④：父子取值(tree_ref/tree_attr) + previous_row_subtotal 的
        // tabType 联动校验，必须在 applyTabType 之后跑(此时 component.tabType 已是最终生效值，
        // component.formulas 也已是本次保存后生效的最终值)。
        assertTreeTokenGates(component.tabType, component.formulas, component.fields);
        // task-0722：行排序列(可空)。非 null 时覆盖(空串=清空)。
        if (request.sortField != null) component.sortField = request.sortField.isBlank() ? null : request.sortField;

        // task-0729 B7：元素编码列/元素单价列/货币列（null=不变，空串=清空，语义同 partNoField）。
        if (request.elementCodeField != null) component.elementCodeField = request.elementCodeField.isBlank() ? null : request.elementCodeField;
        if (request.elementPriceField != null) component.elementPriceField = request.elementPriceField.isBlank() ? null : request.elementPriceField;
        if (request.elementCurrencyField != null) component.elementCurrencyField = request.elementCurrencyField.isBlank() ? null : request.elementCurrencyField;
        // 🔒 保存期校验对象是合并后的最终状态（校验对象是【本次保存后生效的最终状态】，同
        // assertPartNoFieldRequirement 的既定纪律）——sql_views 里任一 sqlTemplate 检测到取价
        // 函数但 elementCodeField/elementPriceField 未配齐 → 400，拦下不让保存（验收 #32②）。
        assertElementBindingRequirement(component);

        // 行键校验（更新路径：软校验，违规只告警不阻断）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, false);

        LOG.infof("Updated component id=%s code=%s", id, component.code);

        // task-0806 B6：H1 自动同步（refreshSnapshotsByComponent）整体退役。
        // 旧注释「组件配置是真理源, snapshot 是缓存视图」正是本任务要推翻的架构漂移——
        // 已发布模板的 snapshot 必须在 publish() 时一次性冻结、此后不可变（见
        // docs/三大核心模块基线.md §3.2 + dev-docs/task-0806-模板发布全量冻结/需求文档.md）。
        // 保存组件不再触碰任何已发布模板的快照；想让新配置生效，只能 createNewDraft → 改 → publish。

        // Bug3 源→副本同步：EXCEL 源组件保存后，把 excelColumns(含 TAB_JOIN 列的 expression/tabs)
        // 刷到所有导入副本。副本无显式外键，靠 code 的 __impN 后缀 + 同 base 识别(与
        // ComponentImportService 同款约定)。模板引用的是副本(Bug1 下拉只列本目录副本)，
        // 故源改公式必须传导到副本，模板保存/报价渲染才能拿到最新列定义。
        try {
            int synced = syncExcelColumnsToImportedCopies(component);
            if (synced > 0) {
                LOG.infof("[Excel source-sync] source=%s synced excelColumns to %d imported copies",
                        component.code, synced);
            }
        } catch (Exception e) {
            // 同步失败不阻断源组件保存；记录警告，副本可由用户重新保存源触发重试。
            LOG.warnf("[Excel source-sync] failed for componentId=%s: %s", id, e.getMessage());
        }

        return ComponentDTO.from(component);
    }

    /**
     * 设置/清空组件的驱动视图。data_driver_path 唯一真源，值形态 $视图名。
     *
     * @param sqlViewName 本组件 ACTIVE SQL 视图名（不含 $）；null/空=清空驱动。
     */
    @Transactional
    public ComponentDTO setDriverView(UUID componentId, String sqlViewName) {
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }
        if (sqlViewName == null || sqlViewName.isBlank()) {
            component.dataDriverPath = null;
        } else {
            String name = sqlViewName.trim();
            boolean exists = sqlViewRepository
                    .findByComponentAndName(componentId, name)
                    .isPresent();
            if (!exists) {
                throw new BusinessException(400,
                        "SQL 视图不存在或未启用：" + name);
            }
            component.dataDriverPath = normalizeDriverPath("$" + name);
        }
        LOG.infof("[driver-view] componentId=%s set dataDriverPath='%s'",
                componentId, component.dataDriverPath);

        // 行键校验（软校验，违规只告警不阻断，与 update() 一致）
        validateRowKeyConfig(component.dataDriverPath, component.fields, component.rowKeyFields, false);

        // task-0806 B6：H1 自动同步（refreshSnapshotsByComponent）整体退役——driver 变更不再
        // 触碰任何已发布模板的快照，同 update() 上方的说明。
        return ComponentDTO.from(component);
    }

    /**
     * 正则：匹配 code 的 __impN 导入副本后缀（与 ComponentImportService.IMP_SUFFIX 同款）。
     * COMP-0035__imp1 → 命中(base=COMP-0035)；COMP-0035 → 不命中(它是源)。
     */
    private static final Pattern IMP_SUFFIX = Pattern.compile("^(.+?)(__imp\\d+)$");

    /** 提取 code 的 base（去掉 __impN 后缀）。COMP-0035__imp1 → COMP-0035；COMP-0035 → COMP-0035。 */
    private static String extractBase(String code) {
        if (code == null) return "";
        Matcher m = IMP_SUFFIX.matcher(code);
        return m.matches() ? m.group(1) : code;
    }

    /**
     * Bug3：把 EXCEL 源组件的 excelColumns 同步到所有导入副本（同 base code 且带 __impN 后缀）。
     *
     * <p>纪律（防 AP-40 多实例污染）：仅当被保存组件本身是<b>源</b>（code 无 __impN 后缀）时才向下传导；
     * 若被保存的是副本则跳过（不反向污染源/兄弟副本）。用 Panache 托管实体逐个赋值，由 Hibernate
     * 脏检查 flush；不走 firstResult，按 base 精确全匹配。
     *
     * @return 被同步的副本数量
     */
    private int syncExcelColumnsToImportedCopies(Component source) {
        if (source == null || !"EXCEL".equals(source.componentType)) return 0;
        // 被保存的是副本 → 不传导（源→副本单向）
        if (IMP_SUFFIX.matcher(source.code).matches()) return 0;
        String base = source.code;
        List<Component> excelComps = Component.list("componentType", "EXCEL");
        int cnt = 0;
        for (Component c : excelComps) {
            if (c.id.equals(source.id)) continue;
            if (!IMP_SUFFIX.matcher(c.code).matches()) continue;   // 只刷副本
            if (!base.equals(extractBase(c.code))) continue;        // 同 base
            if (java.util.Objects.equals(c.excelColumns, source.excelColumns)) continue; // 无变化跳过
            c.excelColumns = source.excelColumns;                   // 托管实体，脏检查自动 flush
            cnt++;
        }
        return cnt;
    }

    @Transactional
    public ComponentDTO toggleStatus(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        if ("ACTIVE".equals(component.status)) {
            component.status = "DISABLED";
            LOG.infof("Disabled component id=%s code=%s", id, component.code);
        } else {
            component.status = "ACTIVE";
            LOG.infof("Enabled component id=%s code=%s", id, component.code);
        }
        return ComponentDTO.from(component);
    }

    @Transactional
    public void delete(UUID id) {
        Component component = Component.findById(id);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + id);
        }
        checkNotReferencedByTemplate(id);
        component.delete();
        LOG.infof("Deleted component id=%s code=%s", id, component.code);
    }

    // -----------------------------------------------------------------------
    // 行键校验（报价单整份快照 Phase 1 §5.1）
    // -----------------------------------------------------------------------

    /**
     * 校验组件的行键配置是否合法。
     *
     * <p>校验触发条件：{@code dataDriverPath} 非空（多行 driver）且 {@code fieldsJson}
     * 含至少一个可录入字段（field_type ∈ EDITABLE_FIELD_TYPES）。
     *
     * <p>豁免：
     * <ul>
     *   <li>单行/固定组件（{@code dataDriverPath} 为空）→ 直接通过</li>
     *   <li>纯只读 driver 组件（无可编辑字段）→ 直接通过</li>
     *   <li>哨兵 {@code ["__seq_no__"]} → 显式豁免（按行号对齐），直接通过</li>
     * </ul>
     *
     * @param dataDriverPath  组件 data_driver_path（BNF 路径或 $xxx_view 引用）
     * @param fieldsJson      组件 fields JSON 字符串（数组）
     * @param rowKeyFieldsJson 组件 row_key_fields JSON 字符串（数组或 null）
     * @param hard            true=新建路径，违规抛 IllegalArgumentException；
     *                        false=更新路径，违规仅 LOG.warn（不阻断存量组件保存）
     */
    public void validateRowKeyConfig(String dataDriverPath, String fieldsJson,
                                     String rowKeyFieldsJson, boolean hard) {
        // 豁免：单行/固定（无 driver）
        if (dataDriverPath == null || dataDriverPath.isBlank()) return;

        com.fasterxml.jackson.databind.JsonNode fields = readJsonNode(fieldsJson);
        boolean hasEditable = false;
        Set<String> fieldNames = new java.util.HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode f : fields) {
            String name = f.path("name").asText(null);
            if (name != null) fieldNames.add(name);
            String ft = f.path("field_type").asText(null);
            if (ft != null && EDITABLE_FIELD_TYPES.contains(ft)) hasEditable = true;
        }
        // 豁免：纯只读 driver（无可编辑字段）
        if (!hasEditable) return;

        com.fasterxml.jackson.databind.JsonNode keys = (rowKeyFieldsJson == null || rowKeyFieldsJson.isBlank())
            ? null : readJsonNode(rowKeyFieldsJson);

        // rowKeyFields 为空数组或 null → 违规
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            failRowKey(hard,
                "含可编辑字段的多行组件（dataDriverPath=" + dataDriverPath + "）必须声明 rowKeyFields");
            return;
        }
        // 哨兵 ["__seq_no__"] → 显式豁免
        if (keys.size() == 1 && "__seq_no__".equals(keys.get(0).asText())) return;

        // 方案 A: rowKeyFields 引用的是 driverRow 的底层列(运行期 expand 才有), 与 fields 中文展示名
        // 属不同命名空间 → 配置期无法对 fields 校验存在性。仅校验每个 key 为非空字符串;
        // driverRow 列名的正确性由配置者/迁移负责(详见 V279 + spec §5.1)。
        for (com.fasterxml.jackson.databind.JsonNode k : keys) {
            String keyName = k.asText(null);
            if (keyName == null || keyName.isBlank()) {
                failRowKey(hard, "rowKeyFields 含空 key（应为 driverRow 的底层列名，如 child_hf_part_no）");
                return;
            }
        }
    }

    /**
     * 树表配置软校验:开启时 idField/parentField 均必填且不同列,且两列须存在于组件字段名集合。
     * 不满足 → IllegalArgumentException(保存阻断)。传 null 或空对象(关闭树表)→ 直接通过。
     */
    public void validateTreeConfig(Map<String, Object> treeConfig, Object fieldsJsonOrList) {
        if (treeConfig == null) return;
        Object idF = treeConfig.get("idField");
        Object pF = treeConfig.get("parentField");
        boolean idEmpty = idF == null || idF.toString().isBlank();
        boolean pEmpty = pF == null || pF.toString().isBlank();
        if (idEmpty && pEmpty) return; // 视为关闭树表
        if (idEmpty || pEmpty) {
            throw new IllegalArgumentException("树表配置:ID 列与父 ID 列均必填(当前仅填了一个)");
        }
        if (idF.toString().equals(pF.toString())) {
            throw new IllegalArgumentException("树表配置:ID 列与父 ID 列不能为同一列");
        }
        java.util.Set<String> names = extractFieldNames(fieldsJsonOrList);
        if (!names.contains(idF.toString()) || !names.contains(pF.toString())) {
            throw new IllegalArgumentException("树表配置:idField/parentField 必须是组件已配置字段名");
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> extractFieldNames(Object fieldsJsonOrList) {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            java.util.List<Map<String, Object>> list;
            if (fieldsJsonOrList instanceof String s) {
                if (s.isBlank()) return names;
                list = MAPPER.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } else if (fieldsJsonOrList instanceof java.util.List<?> l) {
                list = (java.util.List<Map<String, Object>>) l;
            } else return names;
            for (Map<String, Object> f : list) {
                Object nm = f.get("name");
                if (nm != null) names.add(nm.toString());
            }
        } catch (Exception ignore) { }
        return names;
    }

    /** 违规处理：hard=true 抛，hard=false 仅告警。 */
    private void failRowKey(boolean hard, String msg) {
        if (hard) throw new IllegalArgumentException(msg);
        LOG.warnf("[rowKeyFields soft-validation] %s", msg);
    }

    /** 解析 JSON 字符串为 JsonNode；null/空/异常时返回空数组节点。 */
    private com.fasterxml.jackson.databind.JsonNode readJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json: " + e.getMessage(), e);
        }
    }

    // ---- Validation helpers ----

    private void validateRequest(CreateComponentRequest request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BusinessException("Component name is required");
        }
        // code is now auto-generated if not provided
        assertValidComponentType(request.componentType);
    }

    private void validateFields(List<Map<String, Object>> fields) {
        for (Map<String, Object> field : fields) {
            Object fieldType = field.get("field_type");
            if (fieldType == null) {
                throw new BusinessException("Each field must have a field_type");
            }
            if (!VALID_FIELD_TYPES.contains(fieldType.toString())) {
                throw new BusinessException("Invalid field_type: " + fieldType +
                    ". Must be one of: " + VALID_FIELD_TYPES);
            }
            // Plan 3a：条件公式校验 —— 默认必填 + 至少 1 条规则。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object def = cfm.get("default");
                if (def == null || String.valueOf(def).isBlank()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式缺少默认公式（default）");
                }
                Object rules = cfm.get("rules");
                if (!(rules instanceof java.util.List<?> rl) || rl.isEmpty()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式至少需 1 条规则");
                }
            }
            // DATA_SOURCE requires datasource_binding (H2: 4 种 type 各自校验关键配置)
            if ("DATA_SOURCE".equals(fieldType.toString())) {
                Object binding = field.get("datasource_binding");
                if (binding == null) {
                    throw new BusinessException("DATA_SOURCE field requires datasource_binding");
                }
                if (binding instanceof Map) {
                    Map<?, ?> b = (Map<?, ?>) binding;
                    // type 缺省 = DATABASE_QUERY (兼容 H2 前的老配置)
                    String dsType = b.get("type") != null ? b.get("type").toString() : "DATABASE_QUERY";
                    switch (dsType) {
                        case "DATABASE_QUERY":
                            if (b.get("datasource_id") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/DATABASE_QUERY 缺 datasource_id");
                            }
                            break;
                        case "GLOBAL_VARIABLE":
                            if (b.get("global_variable_code") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/GLOBAL_VARIABLE 缺 global_variable_code");
                            }
                            break;
                        case "BNF_PATH":
                            Object p = b.get("bnf_path");
                            if (p == null || p.toString().isBlank()) {
                                throw new BusinessException("DATA_SOURCE/BNF_PATH 缺 bnf_path");
                            }
                            break;
                        case "HTTP_API":
                            Object ac = b.get("api_config");
                            if (!(ac instanceof Map) || ((Map<?, ?>) ac).get("url_template") == null) {
                                throw new BusinessException(
                                    "DATA_SOURCE/HTTP_API 缺 api_config.url_template");
                            }
                            break;
                        default:
                            throw new BusinessException("DATA_SOURCE 不支持的 type: " + dsType);
                    }
                }
            }
        }
        // 多小计列（Plan 2-核心）：不再限制 is_subtotal 数量，每个被标记字段各算一列总计。
    }

    /** Package-private for unit testing (cross_tab_ref structural validation). */
    void validateFormulas(List<Map<String, Object>> fields, List<Map<String, Object>> formulas) {
        validateFormulas(fields, formulas, null);
    }

    /**
     * repair-0803 重载：额外接收<b>组件名称</b>，注入循环引用链路的每个节点
     * （前端抽屉只展示名称，不出现任何 id）。
     *
     * <p>零破坏：两参签名 delegate 到此并传 {@code null} —— 既有单测与其它调用方行为不变，
     * 仅链路里 {@code componentName} 缺省（JSON 按 NON_NULL 省略）。
     */
    void validateFormulas(List<Map<String, Object>> fields, List<Map<String, Object>> formulas,
                          String componentName) {
        // Validate formula names are not empty
        Set<String> formulaNames = new HashSet<>();
        for (Map<String, Object> formula : formulas) {
            Object formulaName = formula.get("name");
            if (formulaName == null || formulaName.toString().isBlank()) {
                throw new BusinessException("公式名称不能为空");
            }
            if (!formulaNames.add(formulaName.toString())) {
                throw new BusinessException("公式名称不能重复: " + formulaName);
            }
        }

        // Validate FORMULA fields: if formula_name is set, it must reference an existing formula
        for (Map<String, Object> field : fields) {
            if (!"FORMULA".equals(field.get("field_type"))) continue;
            Object boundName = field.get("formula_name");
            if (boundName != null && !boundName.toString().isBlank()) {
                if (!formulaNames.contains(boundName.toString())) {
                    throw new BusinessException(
                        "字段 '" + field.get("name") + "' 绑定的公式 '" + boundName + "' 不存在");
                }
            }
            // Plan 3c：条件公式引用校验 —— rules[].formula + default 必须存在。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object rules = cfm.get("rules");
                if (rules instanceof java.util.List<?> rl) {
                    for (Object r : rl) {
                        if (r instanceof Map<?, ?> rm) {
                            Object fn = rm.get("formula");
                            if (fn != null && !fn.toString().isBlank() && !formulaNames.contains(fn.toString())) {
                                throw new BusinessException("字段「" + field.get("name") + "」条件规则引用的公式 '" + fn + "' 不存在");
                            }
                        }
                    }
                }
                Object def = cfm.get("default");
                if (def != null && !def.toString().isBlank() && !formulaNames.contains(def.toString())) {
                    throw new BusinessException("字段「" + field.get("name") + "」默认公式 '" + def + "' 不存在");
                }
            }
        }
        // Plan 3c：硬环检测（含条件依赖）。转 JsonNode 复用引擎依赖图(buildFormulaDeps)。
        // 提示必须可定位：报环路径 + 每条边出自哪条公式 / 哪条条件规则，否则配置员只拿到一个
        // 字段名，得逐条公式翻找（2026-07-28 COMP-0112 反馈）。
        com.fasterxml.jackson.databind.ObjectMapper cycMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode fieldsNode = cycMapper.valueToTree(fields);
        com.fasterxml.jackson.databind.JsonNode formulasNode = cycMapper.valueToTree(formulas);
        com.cpq.quotation.service.FormulaCalculator cycCalc = new com.cpq.quotation.service.FormulaCalculator();
        // repair-0803：message 保持原多行定位文案<b>逐字不变</b>（只读 message 的既有客户端零感知，
        // 亦是 api.md「仅新增、不改既有键」的落地）；额外把同一批环以结构化 cycles 放进 data，
        // 供前端弹链路抽屉（errorType=FORMULA_CYCLE）。两者共用同一套找环逻辑，口径天然一致。
        List<String> cycleTexts = cycCalc.describeFormulaCycles(fieldsNode, formulasNode);
        if (!cycleTexts.isEmpty()) {
            StringBuilder sb = new StringBuilder("公式存在循环引用（")
                .append(cycleTexts.size()).append(" 处），请按以下位置检查：");
            for (int i = 0; i < cycleTexts.size(); i++) {
                sb.append("\n  ").append(i + 1).append(". ").append(cycleTexts.get(i));
            }
            throw new com.cpq.common.exception.FormulaCycleException(sb.toString(),
                cycCalc.describeFormulaCyclesStructured(fieldsNode, formulasNode, componentName));
        }
        // 兜底：描述器万一提取不出环路径，也绝不能放过环——放过 → topoOrder 落进「环兜底」
        // 尾部追加路径 → 依赖未算先算的静默错值（比报错更难发现）。
        List<String> cyclic = cycCalc.cyclicFormulaNodes(fieldsNode, formulasNode);
        if (!cyclic.isEmpty()) {
            throw new BusinessException("公式存在循环引用: " + String.join(", ", cyclic));
        }

        // Validate cross_tab_ref tokens in formula expressions
        for (Map<String, Object> formula : formulas) {
            Object expr = formula.get("expression");
            if (!(expr instanceof List)) continue;
            for (Object operand : (List<?>) expr) {
                if (!(operand instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> token = (Map<String, Object>) operand;
                Object typeObj = token.get("type");
                if (!"cross_tab_ref".equals(typeObj)) continue;

                Object srcObj = token.get("source");
                String src = srcObj == null ? null : srcObj.toString();
                if (src == null || src.isBlank())
                    throw new BusinessException(400, "跨页签引用缺少源组件(source)");

                Object matchObj = token.get("match");
                boolean emptyMatch = !(matchObj instanceof List<?> ml) || ml.isEmpty();
                boolean hasPredicate = token.get("predicate") != null;
                if (emptyMatch && !hasPredicate)   // SUMIF 族用 predicate 过滤，match 可空
                    throw new BusinessException(400, "跨页签引用缺少匹配列(match)");

                Object aggObj = token.get("agg");
                String agg = aggObj == null ? null : aggObj.toString();
                Set<String> okAgg = Set.of("NONE", "SUM", "AVG", "COUNT", "MAX", "MIN");
                if (agg == null || !okAgg.contains(agg.toUpperCase()))
                    throw new BusinessException(400, "跨页签引用聚合方式非法: " + agg);

                Object tgtObj = token.get("target");
                String target = tgtObj == null ? null : tgtObj.toString();
                Object targetExprObj = token.get("targetExpr");
                boolean hasTargetExpr = targetExprObj instanceof java.util.List<?> tl && !tl.isEmpty();
                if (!"COUNT".equalsIgnoreCase(agg) && (target == null || target.isBlank()) && !hasTargetExpr)
                    throw new BusinessException(400, "跨页签引用缺少目标列或目标公式");

                // SUMIF 族：predicate 字段存在时，结构必须可解析（复用模型转换做结构校验）
                Object pred = token.get("predicate");
                if (pred != null) {
                    try {
                        com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                            MAPPER.valueToTree(pred));
                    } catch (Exception e) {
                        throw new BusinessException(400, "cross_tab_ref.predicate 结构非法: " + e.getMessage());
                    }
                }
            }
        }
    }


    private void checkNotReferencedByTemplate(UUID componentId) {
        try {
            Long tableExists = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'template_component'"
            ).getSingleResult();
            if (tableExists == null || tableExists == 0) {
                return;
            }
            Long refCount = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM template_component WHERE component_id = :cid"
            ).setParameter("cid", componentId).getSingleResult();
            if (refCount != null && refCount > 0) {
                throw new BusinessException("Cannot delete component that is referenced by a template");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugf("Template reference check skipped for componentId=%s: %s", componentId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // C1: 全库 BASIC_DATA path↔视图列名审计（只读，不修改数据）
    // -----------------------------------------------------------------------

    /**
     * 审计全库所有组件字段的 {@code default_source.path}，检出 path 末段列名与
     * 该组件 {@code component_sql_view.declared_columns} 不一致的可疑项。
     *
     * <p>只处理形如 {@code $viewName.col} 或 {@code $$compCode.viewName.col} 的路径；
     * 其他形式（无 $ 前缀的 BNF 路径）直接跳过。
     *
     * <p>每个可疑项包含以下字段：
     * <ul>
     *   <li>{@code componentId}     — 组件 UUID</li>
     *   <li>{@code componentCode}   — 组件业务 code</li>
     *   <li>{@code fieldName}       — 字段名（fields[].name）</li>
     *   <li>{@code path}            — 原始 default_source.path</li>
     *   <li>{@code viewName}        — 提取的视图名</li>
     *   <li>{@code columnName}      — 提取的末段列名</li>
     *   <li>{@code issueType}       — "columnMismatch" | "viewNotFound"</li>
     *   <li>{@code actualColumns}   — 视图实际 declared_columns 名称列表（viewNotFound 时为空）</li>
     *   <li>{@code suggestion}      — 可能的修正建议（无等价列时为 null）</li>
     * </ul>
     *
     * @return 可疑项列表（正常项不包含在内）；全部正常时返回空列表
     */
    @Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
    public List<Map<String, Object>> auditBasicDataPaths() {
        // VIEW_PATH_PATTERN 已提升为 class-level static 常量（C1/C3 共用）

        List<Map<String, Object>> suspects = new ArrayList<>();

        List<Component> allComponents = Component.<Component>listAll();

        for (Component comp : allComponents) {
            if (comp.fields == null || comp.fields.isBlank() || "[]".equals(comp.fields.trim())) {
                continue;
            }

            List<Map<String, Object>> fields;
            try {
                fields = MAPPER.readValue(comp.fields, new TypeReference<>() {});
            } catch (Exception e) {
                LOG.warnf("[auditBasicDataPaths] componentId=%s fields JSON 解析失败: %s", comp.id, e.getMessage());
                continue;
            }

            // 按视图名缓存该组件的 declared_columns（避免同视图多字段重复查 DB）
            Map<String, List<String>> viewColumnsCache = new HashMap<>();

            for (Map<String, Object> field : fields) {
                String fieldName = String.valueOf(field.getOrDefault("name", ""));
                Object defaultSource = field.get("default_source");
                if (!(defaultSource instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> ds = (Map<String, Object>) defaultSource;
                Object pathObj = ds.get("path");
                if (pathObj == null) continue;
                String path = pathObj.toString().trim();
                if (path.isEmpty()) continue;

                // 只处理 $ 开头的视图路径
                if (!path.startsWith("$")) continue;

                Matcher m = VIEW_PATH_PATTERN.matcher(path);
                if (!m.matches()) continue;

                // 解析视图名和列名
                String viewName;
                String colName;
                if (m.group(1) != null) {
                    // $$compCode.viewName.col 形态（GLOBAL）
                    // group1=compCode, group2=viewName, group3=col
                    viewName = m.group(2);
                    colName = m.group(3);
                } else {
                    // $viewName.col 形态（COMPONENT）
                    // group4=viewName, group5=col
                    viewName = m.group(4);
                    colName = m.group(5);
                }

                if (viewName == null || colName == null) continue;

                // 取该组件该视图的 declared_columns（带缓存）
                final String finalViewName = viewName;
                List<String> actualColumns = viewColumnsCache.computeIfAbsent(viewName, vn -> {
                    Optional<ComponentSqlView> csv = sqlViewRepository.findByComponentAndName(comp.id, finalViewName);
                    if (csv.isEmpty()) return null; // null 表示视图不存在
                    return extractDeclaredColumnNames(csv.get().declaredColumns);
                });

                Map<String, Object> suspect = new LinkedHashMap<>();
                suspect.put("componentId", comp.id.toString());
                suspect.put("componentCode", comp.code);
                suspect.put("fieldName", fieldName);
                suspect.put("path", path);
                suspect.put("viewName", viewName);
                suspect.put("columnName", colName);

                if (actualColumns == null) {
                    // 视图在 component_sql_view 中不存在
                    suspect.put("issueType", "viewNotFound");
                    suspect.put("actualColumns", Collections.emptyList());
                    suspect.put("suggestion", null);
                    suspects.add(suspect);
                } else if (!actualColumns.contains(colName)) {
                    // 列名与视图列不匹配 — 检查是否有下划线差异等价列
                    suspect.put("issueType", "columnMismatch");
                    suspect.put("actualColumns", actualColumns);
                    suspect.put("suggestion", buildSuggestion(colName, actualColumns, path, viewName));
                    suspects.add(suspect);
                }
                // 精确匹配 → 正常，不加入 suspects
            }
        }

        LOG.infof("[auditBasicDataPaths] 扫描完成，共检出 %d 个可疑项（组件总数=%d）",
            suspects.size(), allComponents.size());
        return suspects;
    }

    // C3: 组件保存时对 default_source.path 列名软校验
    // -----------------------------------------------------------------------

    /**
     * 对字段列表中每个 {@code default_source.path} 的末段列名做软校验：
     * 若列名不在该组件对应视图的 {@code declared_columns} 中，则产生告警（LOG.warnf）
     * 并将告警信息加入返回列表。
     *
     * <p><strong>不阻断保存</strong>：此方法永远不抛异常，存量错误配置可正常保存。
     *
     * <p>仅处理 {@code $viewName.col} / {@code $$compCode.viewName.col} 形态的 path；
     * 其他形态（无 $ 前缀的 BNF 路径）直接跳过。
     *
     * <p>Package-private 供单元测试直接调用断言 warnings。
     *
     * @param componentId 当前组件 UUID（用于查询 component_sql_view）
     * @param fields      反序列化后的字段列表
     * @return warning 字符串列表；无问题时为空列表
     */
    List<String> warnDefaultSourcePaths(UUID componentId, List<Map<String, Object>> fields) {
        if (fields == null || fields.isEmpty() || componentId == null) {
            return Collections.emptyList();
        }

        List<String> warnings = new ArrayList<>();
        // 按视图名缓存该组件的 declared_columns（避免同视图多字段重复查 DB）
        Map<String, List<String>> viewColumnsCache = new HashMap<>();

        for (Map<String, Object> field : fields) {
            // per-field 兜底：单字段校验失败只跳过该字段，绝不影响其余字段及保存事务
            String fieldNameForCatch = String.valueOf(field.getOrDefault("name", ""));
            try {
                Object defaultSource = field.get("default_source");
                if (!(defaultSource instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> ds = (Map<String, Object>) defaultSource;
                Object pathObj = ds.get("path");
                if (pathObj == null) continue;
                String path = pathObj.toString().trim();
                if (path.isEmpty() || !path.startsWith("$")) continue;

                Matcher m = VIEW_PATH_PATTERN.matcher(path);
                if (!m.matches()) continue;

                String viewName;
                String colName;
                if (m.group(1) != null) {
                    // $$compCode.viewName.col 形态
                    viewName = m.group(2);
                    colName  = m.group(3);
                } else {
                    // $viewName.col 形态
                    viewName = m.group(4);
                    colName  = m.group(5);
                }
                if (viewName == null || colName == null) continue;

                String fieldName = String.valueOf(field.getOrDefault("name", ""));
                final String fv = viewName;

                // 取该组件该视图的 declared_columns（带缓存；null 表示视图不存在）
                // computeIfAbsent lambda 内的 DB 查询异常由外层 per-field catch 捕获
                List<String> actualColumns = viewColumnsCache.computeIfAbsent(viewName, vn -> {
                    Optional<ComponentSqlView> csv = sqlViewRepository.findByComponentAndName(componentId, fv);
                    if (csv.isEmpty()) return null;
                    return extractDeclaredColumnNames(csv.get().declaredColumns);
                });

                if (actualColumns == null) {
                    String warn = String.format(
                        "[C3 default_source.path soft-warn] componentId=%s field='%s' path='%s' — 视图 '%s' 未在 component_sql_view 中找到",
                        componentId, fieldName, path, viewName);
                    LOG.warnf("%s", warn);
                    warnings.add(warn);
                } else if (!actualColumns.contains(colName)) {
                    String warn = String.format(
                        "[C3 default_source.path soft-warn] componentId=%s field='%s' path='%s' — 列名 '%s' 不在视图 '%s' 的 declared_columns %s 中",
                        componentId, fieldName, path, colName, viewName, actualColumns);
                    LOG.warnf("%s", warn);
                    warnings.add(warn);
                }
            } catch (Exception e) {
                // 软校验异常（含 DB RuntimeException / PersistenceException）：只告警，绝不逃逸
                LOG.warnf("[default_source.path soft-warn] 校验字段 '%s' 异常(已忽略,不阻断保存): %s",
                        fieldNameForCatch, e.getMessage());
            }
        }

        return warnings;
    }

    /**
     * 从 declared_columns JSON 字符串中提取列名列表。
     * 格式：[{"name":"col","dataType":"text",...}, ...]
     */
    private List<String> extractDeclaredColumnNames(String declaredColumnsJson) {
        if (declaredColumnsJson == null || declaredColumnsJson.isBlank()) return Collections.emptyList();
        try {
            JsonNode arr = MAPPER.readTree(declaredColumnsJson);
            List<String> names = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    JsonNode nameNode = node.get("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        names.add(nameNode.asText());
                    }
                }
            }
            return names;
        } catch (Exception e) {
            LOG.warnf("[declaredColumns parse] declaredColumns JSON 解析失败: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据列名与实际列列表构建修正建议。
     *
     * <p>策略：
     * <ol>
     *   <li>若 col 以 "_" 开头且去掉后命中 actualColumns → 建议去掉下划线</li>
     *   <li>若 col 不以 "_" 开头但加上 "_" 后命中 actualColumns → 建议加下划线</li>
     *   <li>否则无明显等价列 → 返回 null</li>
     * </ol>
     *
     * @return 建议字符串，如 "建议将列名「_类型」改为「类型」"；无等价列时为 null
     */
    private String buildSuggestion(String col, List<String> actualColumns, String path, String viewName) {
        if (col.startsWith("_")) {
            String withoutUnderscore = col.substring(1);
            if (actualColumns.contains(withoutUnderscore)) {
                return String.format("建议将列名「%s」改为「%s」（去掉下划线前缀），即路径改为 $%s.%s",
                    col, withoutUnderscore, viewName, withoutUnderscore);
            }
        } else {
            String withUnderscore = "_" + col;
            if (actualColumns.contains(withUnderscore)) {
                return String.format("建议将列名「%s」改为「%s」（加下划线前缀），即路径改为 $%s.%s",
                    col, withUnderscore, viewName, withUnderscore);
            }
        }
        return null;
    }

    /** 规范化 driver path:剥花括号 + trim,空字符串 → null。 */
    private String normalizeDriverPath(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("{") && t.endsWith("}")) {
            t = t.substring(1, t.length() - 1).trim();
            if (t.isEmpty()) return null;
        }
        return t;
    }

    private String toJson(List<Map<String, Object>> list) {
        if (list == null) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 序列化任意 List 为 JSON（用于 rowKeyFields 等简单 List<String>）。 */
    private String toJsonRaw(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
