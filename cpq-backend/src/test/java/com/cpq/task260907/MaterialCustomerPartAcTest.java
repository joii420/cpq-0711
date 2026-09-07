package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-1 / AC-2 / AC-6</b> —— F-1「客户料号并入『物料』数据源，物料为主 + LEFT JOIN」。
 *
 * <h3>AC 原文（需求文档.md §③）</h3>
 * <ul>
 *   <li><b>AC-1（物料数据源出两张表的列）</b>：① 同时出现 {@code ds_quote_material} 与
 *       {@code ds_quote_customer_part} 两张表的列；② 客户料号侧<b>至少</b>可拖
 *       {@code customer_no / customer_part_name / customer_product_no / customer_drawing_no} 四列；
 *       ③「本数据集有 N 张表不进语义图」提示整条消失（或 N=0），至少不再出现 {@code ds_quote_customer_part}。</li>
 *   <li><b>AC-2（1:1 左连，一行不丢一行不多）</b>：① 预览行数 = 47（物料为主，不是 19）；
 *       ② 那 28 个没有客户料号的物料行照常出现、客户料号列为空；
 *       ③ 生成的 SQL 含 {@code LEFT JOIN}，ON 条件用 {@code material_no}；④ 不放大行数。</li>
 *   <li><b>AC-6（边界）</b>：① 确定没有客户料号的物料行出现、客户列为空（不是整行消失也不是报错）；
 *       ② 客户料号表为空时「物料」数据源仍能正常预览（LEFT JOIN 退化，不报错）。</li>
 * </ul>
 *
 * <h3>🚨 AC-2① 的可执行性问题（本轮实测，已报主线）</h3>
 * AC-2① 写「预览行数 = 47」，但 {@code POST /builder/preview} 是<b>按 {@code partNo} 收窄</b>的：
 * <b>不传 {@code partNo} 时 {@code rowCount} 恒为 0</b>（2026-09-07 亲测，轴 {@code :total_material_no} 为空数组）。
 * ⇒ 「47 行」在预览端点上根本复现不出来；照 AC 字面写会得到一条<b>在 0 上恒成立</b>的空断言。
 * <p>⇒ 本类改为把配置器<b>真实产出的 SQL</b> 绑到全量轴（{@code ds_quote_material} 全部料号）上执行，
 * 验 AC-2 的实质：<b>行数 = 主表行数（不丢）· 不放大 · 无客户料号的行照常在且客户列为 NULL</b>。
 * 47 这个数字<b>执行期现算</b>，🚫 不写死。
 *
 * <h3>🚨 假绿防线</h3>
 * <ol>
 *   <li><b>LEFT/INNER 可分辨性守卫</b>：若库里恰好「每个物料都有客户料号」，LEFT 与 INNER 行数相同，
 *       本条就<b>验不出 LEFT</b> —— 用例必须先断言两者不同，否则判【未验证】而不是【通过】。</li>
 *   <li><b>方言隔离</b>：api.md §1.3 明写只有 QUOTE 新增该组 ⇒ 反向断言 COST_BASIC/COST_DETAIL 不出现。</li>
 *   <li>所有集合断言一律「先证明非空，再证明合格」。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260907 · AC-1/2/6 —— 物料数据源出两张表的列，物料为主 LEFT JOIN 客户料号")
class MaterialCustomerPartAcTest extends Task260907Base {

    /** 「物料」数据源的坐标。执行期从库里反查，🚫 不写死「主件」。 */
    private String[] materialCoordinate() {
        List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,''), n.node_key "
                + "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND n.physical_table='" + MATERIAL_TABLE + "'");
        assertFalse(rs.isEmpty(), "前置未满足：QUOTE 下找不到锚点表 = " + MATERIAL_TABLE + " 的 ACTIVE 坐标 "
                + "⇒ 「物料」数据源不存在，本类全部断言会空跑。这是地基故障，不是 AC 结论。");
        assertEquals(1, rs.size(), "前置：QUOTE 下锚点表 = " + MATERIAL_TABLE + " 的坐标应唯一，实际=" + rs.size());
        Object[] r = rs.get(0);
        return new String[]{String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[2])};
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-1① ②：字段面板同时出两张表的列
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1①②：QUOTE「物料」的字段面板同时出 ds_quote_material 与 ds_quote_customer_part 两张表的列，"
            + "客户料号四列至少全在；且分组数 ≥ 2（api.md §1.3：MAIN + 新增 AUX）")
    void ac1_materialPanelCarriesBothTables() {
        assertDialectParamIsHonored("AC-1");   // 阳性对照：dialect 真被消费

        String[] coord = materialCoordinate();
        List<Group> groups = groupsOf(coord[0], coord[1], "QUOTE", "AC-1");
        Set<String> cols = allColumnsOf(groups);
        System.out.println("[AC-1] QUOTE/" + coord[0] + "/" + coord[1] + " 分组=" + groups.size()
                + " 列数=" + cols.size() + " 分组明细="
                + groups.stream().map(g -> g.groupKey() + "(" + g.groupKind() + "," + g.sourceColumns().size() + ")").toList()
                + "  🚫 数字仅记录，不作断言");

        // ── ① 物料表的列在（阳性对照：先证明面板本来就有东西，否则下面「还应有客户列」会红成另一件事）──
        Set<String> materialBiz = businessColumns(MATERIAL_TABLE);
        assertNonEmptyThenAllMatch(cols, materialBiz, "QUOTE/物料 面板列", "AC-1① 前置(物料侧)");

        // ── ① + ② 客户料号表的四列在 ──
        assertNonEmptyThenAllMatch(cols, AC1_CUSTOMER_PART_COLUMNS, "QUOTE/物料 面板列", "AC-1①②");

        // ── 分组结构：至少两组，且客户料号四列聚在同一组里（api.md §1.3：新增一个 AUX 组）──
        assertTrue(groups.size() >= 2, "AC-1①：「物料」数据源的 groups 应由 1 组变为 ≥2 组（api.md §1.3），实际="
                + groups.size() + " 明细=" + groups);
        List<Group> holders = groups.stream()
                .filter(g -> g.sourceColumns().containsAll(AC1_CUSTOMER_PART_COLUMNS)).toList();
        assertEquals(1, holders.size(), "AC-1②：客户料号四列应聚在同一个分组里（面板要能整组折叠），"
                + "实际承载这四列的分组数=" + holders.size() + " 分组=" + groups);
        System.out.println("[AC-1] ✅ 客户料号组 groupKey=" + holders.get(0).groupKey()
                + " groupKind=" + holders.get(0).groupKind() + " 列=" + holders.get(0).sourceColumns());
    }

    @Test
    @DisplayName("AC-1（反向 · api.md §1.3）：核价两方言的「物料」面板【不】出现客户料号列 —— 本期只接报价侧")
    void ac1_customerPartOnlyInQuoteDialect() {
        for (String dialect : List.of("COST_BASIC", "COST_DETAIL")) {
            List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,'') FROM semantic_tab_view v "
                    + "JOIN semantic_node n ON n.id=v.anchor_node_id WHERE v.dialect='" + dialect
                    + "' AND v.status='ACTIVE' AND n.physical_table='" + MATERIAL_TABLE + "'");
            if (rs.isEmpty()) {
                System.out.println("[AC-1反向] " + dialect + " 无锚点表=" + MATERIAL_TABLE + " 的坐标，跳过（不构成失败）");
                continue;
            }
            String tabType = String.valueOf(rs.get(0)[0]);
            String variantKey = String.valueOf(rs.get(0)[1]);
            Set<String> cols = allColumnsOf(groupsOf(tabType, variantKey, dialect, "AC-1反向(" + dialect + ")"));
            assertFalse(cols.isEmpty(), "AC-1反向：" + dialect + " 的面板列为空 ⇒「不含客户列」会恒真通过（空跑）。");
            List<String> leaked = AC1_CUSTOMER_PART_COLUMNS.stream().filter(cols::contains).toList();
            assertTrue(leaked.isEmpty(), "AC-1反向：api.md §1.3 明写「仅 dialect=QUOTE 新增该组」，"
                    + "但 " + dialect + " 的「物料」面板出现了客户料号列 " + leaked + "。实际列=" + cols);
            System.out.println("[AC-1反向] ✅ " + dialect + " 未泄漏客户料号列（面板列数=" + cols.size() + "）");
        }
    }

    @Test
    @DisplayName("AC-1③（结构层）：四张原本『不进语义图』的表全部在 QUOTE 语义图里可达 —— "
            + "UI 那条『N 张表不进语义图』提示的消失由 E2E 断言")
    void ac1_fourTablesNowReachableInSemanticGraph() {
        List<String> targets = new ArrayList<>();
        targets.add(CUSTOMER_PART_TABLE);
        targets.addAll(ANNUAL_TABLES);

        // 先证明「语义图里 QUOTE 侧有节点」这个集合非空，否则下面的 contains 判断没有意义
        Set<String> reachable = new LinkedHashSet<>(strCol(
                "SELECT DISTINCT n.physical_table FROM semantic_node n WHERE n.status='ACTIVE'"));
        assertFalse(reachable.isEmpty(), "AC-1③：semantic_node 里没有任何 ACTIVE 节点 ⇒ 断言空跑");

        List<String> missing = targets.stream().filter(t -> !reachable.contains(t)).toList();
        assertTrue(missing.isEmpty(), "AC-1③：以下表仍不在语义图里（配置器里拖不到）=" + missing
                + "。四张全部接入后，面板那条『本数据集有 N 张表不进语义图』提示才会整条消失。"
                + "当前语义图覆盖的表数=" + reachable.size());
        System.out.println("[AC-1③] ✅ 四张表全部进图：" + targets);
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-2 / AC-6：LEFT JOIN 的行为
    // ═══════════════════════════════════════════════════════════════════

    /** 「物料 + 客户料号」的配置：物料侧料号 + 品名，客户侧 customer_no。 */
    private String materialPlusCustomerCfg(String tabType, String variantKey, String materialNodeKey, String custNodeKey) {
        return cfg("QUOTE", tabType, variantKey,
                colJson(materialNodeKey, "material_no", "料号", true, true),
                colJson(materialNodeKey, "material_name", "品名"),
                colJson(custNodeKey, "customer_no", "客户编号"),
                colJson(custNodeKey, "customer_product_no", "客户产品号"));
    }

    /** 客户料号组所属节点的 node_key（执行期反查，🚫 不写死 {@code CUSTOMER_PART}）。 */
    private String customerPartNodeKey() {
        String k = scalar("SELECT n.node_key FROM semantic_node n WHERE n.status='ACTIVE' "
                + "AND n.physical_table='" + CUSTOMER_PART_TABLE + "' ORDER BY n.node_key LIMIT 1");
        assertNotNull(k, "前置未满足：语义图里没有 physical_table=" + CUSTOMER_PART_TABLE + " 的 ACTIVE 节点 "
                + "⇒ B-1 尚未落地或节点被停用。AC-2/AC-6 无输入可用。");
        return k;
    }

    @Test
    @DisplayName("AC-2③：生成的 SQL 含 LEFT JOIN，ON 条件用 material_no（🚫 不是 INNER，🚫 不是别的键）")
    void ac2_generatedSqlUsesLeftJoinOnMaterialNo() {
        String[] coord = materialCoordinate();
        UUID cid = createBlankComponent("ac2sql");
        String sql = compileSql(cid, materialPlusCustomerCfg(coord[0], coord[1], coord[2], customerPartNodeKey()), "AC-2③");
        System.out.println("[AC-2③] 编译产物:\n" + sql);

        String upper = sql.toUpperCase();
        assertTrue(upper.contains("LEFT JOIN"), "AC-2③：生成的 SQL 应含 LEFT JOIN（物料为主 + 左连客户料号，"
                + "用户 2026-09-07 裁决）。实际 SQL=\n" + sql);
        // ON 条件必须落在 material_no 上；顺带排除「写成 INNER JOIN」这一最可能的错法
        assertTrue(sql.contains("material_no"), "AC-2③：SQL 里没有 material_no ⇒ ON 条件不可能用它。SQL=\n" + sql);
        int leftIdx = upper.indexOf("LEFT JOIN");
        String tail = sql.substring(leftIdx);
        assertTrue(tail.toUpperCase().contains(" ON ") && tail.contains("material_no"),
                "AC-2③：LEFT JOIN 之后没有以 material_no 为键的 ON 条件。SQL 尾段=\n" + tail);
        assertFalse(upper.contains("INNER JOIN " + CUSTOMER_PART_TABLE.toUpperCase()),
                "AC-2③：客户料号表被 INNER JOIN 进来了 ⇒ 没有客户料号的物料会整行消失。SQL=\n" + sql);

        // 🚦 2026-09-07 用户裁决追加：客户料号侧要带客户谓词 customer_no = :customerCode
        assertTrue(sql.contains(":customerCode"), "AC-2③（2026-09-07 用户裁决追加）：选了客户料号侧的列后，"
                + "生成的 SQL 应带客户谓词 :customerCode —— 否则同一料号会把别的客户的客户料号带出来"
                + "（本项目出过森萨塔跨客户串号故障）。SQL=\n" + sql);
        assertTrue(sql.contains("customer_no"), "AC-2③：客户谓词应落在 customer_no 上。SQL=\n" + sql);
    }

    @Test
    @DisplayName("AC-2①② + AC-6①：把配置器产出的 SQL 绑到全量轴执行 —— "
            + "【每个物料都不丢】（LEFT 而非 INNER），且没有客户料号的物料客户列为 NULL")
    void ac2_leftJoinLosesNoMaterialRow() {
        String[] coord = materialCoordinate();
        String custNode = customerPartNodeKey();

        // ── 现算期望值（🚫 不写死 47/19/28，它们是 2026-09-07 快照且已漂）──
        long materialRows = count("SELECT count(*) FROM " + MATERIAL_TABLE);
        long materialsWithCustomerPart = count("SELECT count(DISTINCT m.material_no) FROM " + MATERIAL_TABLE
                + " m JOIN " + CUSTOMER_PART_TABLE + " c ON c.material_no = m.material_no");
        long fanoutMaterials = count("SELECT count(*) FROM (SELECT material_no FROM " + CUSTOMER_PART_TABLE
                + " WHERE material_no IS NOT NULL GROUP BY material_no HAVING count(*) > 1) t");
        System.out.println("[AC-2] 现算：" + MATERIAL_TABLE + "=" + materialRows + " 行；有客户料号的物料="
                + materialsWithCustomerPart + "；一个料号对多条客户料号的=" + fanoutMaterials);

        // ── 🚨 可分辨性守卫：LEFT 与 INNER 必须能分辨，否则本条验不到「LEFT」──
        assertTrue(materialRows > 0, "AC-2 前置：" + MATERIAL_TABLE + " 为空 ⇒ 所有断言在 0 上恒成立（空跑）");
        assertTrue(materialsWithCustomerPart < materialRows,
                "AC-2 【未验证】：当前数据下每个物料都有客户料号（" + materialsWithCustomerPart + "/" + materialRows
                        + "）⇒ LEFT 与 INNER 行为相同，本条分辨不出用的是哪种连接。数据前置缺失，不是「通过」。");

        UUID cid = createBlankComponent("ac2run");
        String sql = compileSql(cid, materialPlusCustomerCfg(coord[0], coord[1], coord[2], custNode), "AC-2");
        List<String> axis = strCol("SELECT material_no FROM " + MATERIAL_TABLE + " ORDER BY material_no");

        // ① 不丢：每个物料至少出现一次（🚫 不断言「恰好 N 行」——
        //    2026-09-07 用户裁决已否掉「不放大」口径：uq(customer_no, customer_product_no) 不含 material_no，
        //    一个料号挂多个客户产品编号是 schema 允许的，放大是合法结果。）
        // 🔑 客户谓词的取值：取「客户料号命中数最多」的那个客户号，现算，🚫 不写死
        String cust = scalar("SELECT c.customer_no FROM " + CUSTOMER_PART_TABLE + " c "
                + "JOIN " + MATERIAL_TABLE + " m ON m.material_no = c.material_no "
                + "GROUP BY c.customer_no ORDER BY count(*) DESC LIMIT 1");
        assertNotNull(cust, "AC-2 前置：找不到任何「客户料号能 JOIN 上物料」的客户号 ⇒ 客户侧断言会空跑");
        long custHits = count("SELECT count(DISTINCT m.material_no) FROM " + MATERIAL_TABLE + " m JOIN "
                + CUSTOMER_PART_TABLE + " c ON c.material_no = m.material_no WHERE c.customer_no='" + cust + "'");
        System.out.println("[AC-2] 客户谓词取值 customerCode=" + cust + "（该客户能命中 " + custHits + " 个物料）");
        assertTrue(custHits > 0, "AC-2 前置：选定客户命中 0 个物料 ⇒ 客户列全 NULL，断言分辨不出 LEFT/INNER");

        long distinctParts = ((Number) em.createNativeQuery(
                "SELECT count(DISTINCT t.hf_part_no) FROM (" + bindAxis(sql, axis, cust) + ") t").getSingleResult()).longValue();
        long produced = runCompiledCount(sql, axis, cust);
        System.out.println("[AC-2①] 全量轴 → 总行数=" + produced + " 覆盖到的物料数=" + distinctParts
                + "（物料表 " + materialRows + " 行；🚩 总行数 ≥ 物料数属合法：一个料号可挂多个客户产品编号）");

        assertEquals(materialRows, distinctParts, "AC-2①：应以物料为主 —— 每个物料都要出现（LEFT JOIN），"
                + "实际只覆盖了 " + distinctParts + " / " + materialRows + " 个物料。"
                + "若等于 " + materialsWithCustomerPart + " 则是 INNER JOIN（丢了没有客户料号的物料）。\nSQL=\n" + sql);
        assertTrue(produced >= materialRows, "AC-2①：总行数 " + produced + " 少于物料数 " + materialRows + " ⇒ 有物料被丢掉");

        // ② + AC-6①：没有客户料号的物料，客户列为 NULL
        String custCol = customerColumnAlias(sql);
        long nullCustomer = ((Number) em.createNativeQuery(
                "SELECT count(DISTINCT t.hf_part_no) FROM (" + bindAxis(sql, axis, cust) + ") t WHERE t." + custCol + " IS NULL")
                .getSingleResult()).longValue();
        // 加了客户谓词后，「客户列为空」的物料 = 全部物料 − 该客户命中的物料
        long expectedNull = materialRows - custHits;
        System.out.println("[AC-2②/AC-6①] 客户列为 NULL 的物料数=" + nullCustomer + "（期望 " + expectedNull
                + " = 物料 " + materialRows + " − 客户 " + cust + " 命中 " + custHits + "）");
        assertTrue(expectedNull > 0, "AC-2② 前置：期望为 NULL 的物料数为 0 ⇒ 本条会空跑");
        assertEquals(expectedNull, nullCustomer, "AC-2②/AC-6①：没有客户料号的物料应照常出现且客户列为空，"
                + "期望 " + expectedNull + " 个（= 物料 " + materialRows
                + " − 该客户命中 " + custHits + "），实际=" + nullCustomer);
    }

    @Test
    @DisplayName("AC-2④ 现状取证（🚫 不作『不放大』断言 —— 2026-09-07 用户裁决已否掉该口径）："
            + "一个物料挂两条【同客户】的客户料号时，行数会翻倍；记录事实，并守住「加了客户谓词也解决不了」这个前提")
    void ac2_oneToManyIsSchemaAllowed_evidenceOnly() {
        String[] coord = materialCoordinate();
        String custNode = customerPartNodeKey();
        BomFx f = buildBomFixture("ac2n", false);   // root 已有 1 条客户料号

        // 再给 root 加第二条【同客户】的客户料号（换 customer_product_no ⇒ 不撞 uq_ds_quote_customer_part）
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "INSERT INTO " + CUSTOMER_PART_TABLE
                                + " (customer_no, customer_part_name, customer_product_no, customer_drawing_no, material_no, source, created_at) "
                                + "VALUES (:cn,:pn,:cpn,:dn,:mn,'TEST-t260907',now())")
                .setParameter("cn", f.customerNo).setParameter("pn", PREFIX + "客户品名2")
                .setParameter("cpn", f.customerProductNo + "-2").setParameter("dn", PREFIX + "图号2")
                .setParameter("mn", f.root).executeUpdate());

        long cpRows = count("SELECT count(*) FROM " + CUSTOMER_PART_TABLE + " WHERE material_no='" + f.root + "'");
        long cpCustomers = count("SELECT count(DISTINCT customer_no) FROM " + CUSTOMER_PART_TABLE
                + " WHERE material_no='" + f.root + "'");
        assertEquals(2L, cpRows, "构造自检：应造出 2 条客户料号");
        assertEquals(1L, cpCustomers, "构造自检：这 2 条必须属于【同一个客户】——"
                + "否则本条验不到「加客户谓词也挡不住放大」这个关键点");

        UUID cid = createBlankComponent("ac2n");
        String sql = compileSql(cid, materialPlusCustomerCfg(coord[0], coord[1], coord[2], custNode), "AC-2④取证");
        long produced = runCompiledCount(sql, List.of(f.root), f.customerNo);
        System.out.println("[AC-2④取证] 物料 " + f.root + " 挂 " + cpRows + " 条【同客户】客户料号 → SQL 返回 "
                + produced + " 行");

        // ✅ 仍然断言的是「不丢」这一侧（未被裁决改动）
        assertTrue(produced >= 1, "AC-2①：物料行不得丢失，实际返回 " + produced + " 行");

        // 🚩 现状留档：不作通过/失败判据
        if (produced > 1) {
            System.out.println("[AC-2④取证] 🚩 行数被放大到 " + produced + " —— 这是 **schema 允许的合法结果**："
                    + "uq_ds_quote_customer_part(customer_no, customer_product_no) 不含 material_no，"
                    + "一个料号挂多个客户产品编号合法；且这 2 行同属一个客户 ⇒ "
                    + "**加 customer_no = :customerCode 谓词也挡不住**。"
                    + "⇒ 需求文档 AC-2④『不得 > 47』的口径已被用户 2026-09-07 裁决否掉，本用例不再据此判失败。");
        }
    }

    /** 从编译产物里认出客户侧列的输出别名（🚫 不写死中文别名 —— 它由 fieldName 决定）。 */
    private String customerColumnAlias(String sql) {
        // 输出别名形如 "_物料_客户编号" / "_客户料号_客户编号"；这里按 fieldName「客户编号」反查
        int i = sql.indexOf("客户编号");
        assertTrue(i > 0, "构造自检：编译产物里找不到 fieldName「客户编号」对应的输出列 ⇒ 客户侧列压根没被编译进来。SQL=\n" + sql);
        int end = sql.indexOf('"', i);
        int start = sql.lastIndexOf('"', i);
        assertTrue(start >= 0 && end > start, "构造自检：无法解析客户列别名。SQL=\n" + sql);
        return sql.substring(start, end + 1);
    }

    @Test
    @DisplayName("AC-6①（边界 · 走真实 preview 端点）：一个【确定没有】客户料号的物料，"
            + "预览必须返回该行且客户列为空 —— 不是整行消失，也不是报错")
    void ac6_materialWithoutCustomerPartStillPreviews() {
        String[] coord = materialCoordinate();
        String custNode = customerPartNodeKey();
        BomFx f = buildBomFixture("ac6", false);   // 夹具：只有 root 有客户料号，c1 没有

        // 构造自检（阳性对照）：c1 确实没有客户料号、root 确实有
        assertEquals(0L, count("SELECT count(*) FROM " + CUSTOMER_PART_TABLE + " WHERE material_no='" + f.c1 + "'"),
                "构造自检：夹具 c1 不该有客户料号，否则本条验的不是「无客户料号」分支");
        assertEquals(1L, count("SELECT count(*) FROM " + CUSTOMER_PART_TABLE + " WHERE material_no='" + f.root + "'"),
                "构造自检：夹具 root 应有 1 条客户料号（用于对照）");

        UUID cid = createBlankComponent("ac6");
        String config = materialPlusCustomerCfg(coord[0], coord[1], coord[2], custNode);

        // 有客户料号的一侧（对照组）—— 先证明这条路径本来能取到非空数据
        List<Map<String, Object>> withCp = previewRows(cid, config, f.root, f.customerNo, "AC-6①对照");
        assertFalse(withCp.isEmpty(), "AC-6① 对照组：有客户料号的物料 " + f.root + " 预览返回 0 行 "
                + "⇒ 取数路径本身不通，此时「无客户料号也能出行」的断言不可信。");
        System.out.println("[AC-6①对照] " + f.root + " → " + withCp);

        // 无客户料号的一侧（被测）
        List<Map<String, Object>> without = previewRows(cid, config, f.c1, f.customerNo, "AC-6①");
        System.out.println("[AC-6①] " + f.c1 + " → " + without);
        assertFalse(without.isEmpty(), "AC-6①：没有客户料号的物料 " + f.c1 + " 预览返回 0 行 "
                + "⇒ 整行被 INNER JOIN 掉了。LEFT JOIN 必须保留它。");
        assertEquals(1, without.size(), "AC-6①：应恰好 1 行，实际=" + without.size() + " → " + without);

        Map<String, Object> row = without.get(0);
        String custKey = row.keySet().stream().filter(k -> k.contains("客户编号")).findFirst().orElse(null);
        assertNotNull(custKey, "AC-6①：预览行里没有客户编号列 ⇒ 客户侧列没被编译进输出。row=" + row);
        assertNull(row.get(custKey), "AC-6①：无客户料号的物料，客户编号列应为空，实际=" + row.get(custKey) + " row=" + row);
    }

    @Test
    @DisplayName("AC-6②（边界 · 空表退化）：客户料号表里【没有任何一行】命中本轴时，"
            + "「物料」数据源仍返回全部物料行、不报错（LEFT JOIN 退化）")
    void ac6_emptyCustomerPartDegradesGracefully() {
        // 🚫 不清空共享库的 ds_quote_customer_part（那是 §3.2 环境销毁红线）。
        //    等价构造：用一批【确定没有任何客户料号】的自建料号当轴 —— 对这份 SQL 而言，
        //    「客户料号表为空」与「客户料号表里没有命中本轴的行」是同一个执行分支。
        String[] coord = materialCoordinate();
        BomFx f = buildBomFixture("ac6b", false);
        List<String> axis = List.of(f.c1, f.c2, f.grandchild);   // 三者都没有客户料号
        assertEquals(0L, count("SELECT count(*) FROM " + CUSTOMER_PART_TABLE
                        + " WHERE material_no IN ('" + String.join("','", axis) + "')"),
                "构造自检：本轴上不应存在任何客户料号行，否则验的不是退化分支");

        UUID cid = createBlankComponent("ac6b");
        String sql = compileSql(cid, materialPlusCustomerCfg(coord[0], coord[1], coord[2], customerPartNodeKey()), "AC-6②");
        long produced = runCompiledCount(sql, axis, f.customerNo);
        System.out.println("[AC-6②] 轴=" + axis + " → " + produced + " 行（期望 " + axis.size() + "）");
        assertEquals(axis.size(), produced, "AC-6②：客户料号侧无任何匹配行时，「物料」数据源仍应返回全部物料行"
                + "（LEFT JOIN 退化），实际=" + produced + "\nSQL=\n" + sql);
    }
}
