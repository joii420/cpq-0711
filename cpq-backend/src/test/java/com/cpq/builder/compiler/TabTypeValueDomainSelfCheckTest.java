package com.cpq.builder.compiler;

import com.cpq.component.service.ComponentService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260819 · B-58b：把「页签类型<b>存储值</b>」这条裁决从文档钉进构建期（需求文档 <b>D-39</b>）。
 *
 * <h3>为什么要有这一条：同一个缺陷犯到了第三次</h3>
 * <ol>
 *   <li>前端 {@code TAB_TYPES} 常量写成 {@code "BOM 树"} ⇒ {@code includes()} 静默 miss，
 *       存量 {@code tab_type='BOM'} 的组件打开取数配置 Tab 被误初始化成「主件」（D-39 就是为它写的）；</li>
 *   <li>{@code V413} 种子把显示名写进 {@code semantic_tab_view.tab_type} ⇒ {@code PUT /builder} 400，
 *       「BOM 树」页签的组件根本存不进去（D-128，{@code V417} 修）；</li>
 *   <li>{@link FieldTreeBuilder#ALL_TAB_TYPES} 同一个错 —— <b>本条</b>。</li>
 * </ol>
 *
 * <h3>为什么现有的 B-56 自检抓不到它</h3>
 * {@code SemanticGraphKeyValueSelfCheck} 钉的是<b>库侧</b>：
 * {@code semantic_tab_view.tab_type ⊆ ComponentService.VALID_TAB_TYPES}。
 * 它按取值分组查 DB，<b>看不见任何 Java 常量</b>。三次事故里它只覆盖第 2 次。
 * ⇒ 缺的是一道<b>代码常量 ⇄ 权威值域</b>的闸，本类就是它。
 *
 * <h3>为什么落在普通 JUnit，而不是并进启动期自检</h3>
 * <ul>
 *   <li><b>边界</b>：{@code SemanticGraphKeyValueSelfCheck} 的类注释把自己的职责写死为
 *       「校验<b>语义图键值列</b>」，每条规则是一个 {@code (表, 列, 值域)} 三元组。
 *       Java 常量既没有表也没有列，塞进去要么改坏 {@code KeyColumnRule} 的形状，
 *       要么在那份「取舍表」里凭空多一行不讲表列的例外 —— 那份表的可读性正是它的价值所在。</li>
 *   <li><b>时机</b>：本断言两侧都是<b>编译期常量</b>，不随环境/数据变化。
 *       构建期就能判死的事推到启动期才判，只会更晚发现，不会更早。</li>
 *   <li><b>依赖</b>：启动期自检要拿 {@code DataSource} 连库；本类零 Quarkus、零 DB
 *       （{@code application-test.properties} 默认库就是<b>共享开发库</b> {@code cpq_db_0724}，能不碰就不碰）。</li>
 * </ul>
 * 另考虑过写进 {@code FieldTreeBuilder} 的 static 初始化块（类加载即炸），已否决：
 * 纯静态条件换来一个 CDI 类加载期异常，堆栈对人极不友好，收益不抵噪声。
 *
 * <p>🚫 <b>本类失败时的正确动作是把常量改对</b>，不是放宽这里的断言。
 */
class TabTypeValueDomainSelfCheckTest {

    /**
     * {@code ALL_TAB_TYPES} 必须<b>全部是存储值</b>——即它是权威值域的子集。
     * 越界元素的典型形态就是显示名（「BOM 树」）混了进来。
     */
    @Test
    void allTabTypesMustBeStorageValues() {
        Set<String> domain = ComponentService.VALID_TAB_TYPES;
        Set<String> offenders = new LinkedHashSet<>(FieldTreeBuilder.ALL_TAB_TYPES);
        offenders.removeAll(domain);

        System.out.println("---- B-58b 值域比对 ----");
        System.out.println("  FieldTreeBuilder.ALL_TAB_TYPES        = " + FieldTreeBuilder.ALL_TAB_TYPES);
        System.out.println("  ComponentService.VALID_TAB_TYPES      = " + new TreeSet<>(domain));
        System.out.println("  越界（不是存储值）                     = " + offenders);

        assertTrue(offenders.isEmpty(),
                "FieldTreeBuilder.ALL_TAB_TYPES 含非法页签类型 " + offenders
                        + "，不在权威值域 " + new TreeSet<>(domain) + " 内。\n"
                        + "  这多半是把**显示名**（如「BOM 树」）当成了**存储值**（应为「BOM」）——"
                        + "需求文档 D-39 明确二者分离，且这已是第三次。\n"
                        + "  后果：tabTypesFallback 分支会把该值当可选项发给前端，用户选中后 "
                        + "PUT /api/cpq/components/{id}/builder 被 assertValidTabType 判 400 Invalid tabType；\n"
                        + "        正常路径的展示顺序收窄里它永不匹配，真值被挤到 availableTabTypes 末位。\n"
                        + "  🚫 正确动作是把常量改成存储值，不是放宽本断言、更不是改 VALID_TAB_TYPES"
                        + "（D-39：现网已有该值的数据，不可改）。");
    }

    /**
     * 反向漂移：值域里新增了一类、{@code ALL_TAB_TYPES} 没跟上。
     *
     * <p>这个方向<b>子集断言抓不到</b>（少一类仍然是子集），失败形态也不报错——
     * 只是那一类页签在字段面板的 {@code availableTabTypes} 里<b>永远不出现</b>，用户以为自己没有。
     * 故必须单独钉「个数相等」，与上一条合起来即<b>集合相等</b>。
     */
    @Test
    void allTabTypesMustCoverTheWholeDomain() {
        Set<String> domain = ComponentService.VALID_TAB_TYPES;
        Set<String> asSet = new LinkedHashSet<>(FieldTreeBuilder.ALL_TAB_TYPES);

        // 先排除「列表里有重复元素」这种会让个数比对失真的情况。
        assertEquals(FieldTreeBuilder.ALL_TAB_TYPES.size(), asSet.size(),
                "FieldTreeBuilder.ALL_TAB_TYPES 有重复元素：" + FieldTreeBuilder.ALL_TAB_TYPES);

        Set<String> missing = new LinkedHashSet<>(domain);
        missing.removeAll(asSet);

        System.out.println("---- B-58b 覆盖比对 ----");
        System.out.println("  值域有、ALL_TAB_TYPES 缺 = " + missing);
        System.out.println("  个数 " + asSet.size() + " vs 值域 " + domain.size());

        assertTrue(missing.isEmpty(),
                "ComponentService.VALID_TAB_TYPES 里的 " + missing
                        + " 未出现在 FieldTreeBuilder.ALL_TAB_TYPES 中。\n"
                        + "  后果不是报错而是**静默缺席**：这些页签类型永远不会出现在字段面板的 "
                        + "availableTabTypes 里（兜底全量、展示顺序收窄都以 ALL_TAB_TYPES 为准）。");
        assertEquals(domain.size(), asSet.size(),
                "两侧个数必须一致（合起来 = 集合相等）。ALL_TAB_TYPES=" + asSet
                        + " / VALID_TAB_TYPES=" + new TreeSet<>(domain));
    }
}
