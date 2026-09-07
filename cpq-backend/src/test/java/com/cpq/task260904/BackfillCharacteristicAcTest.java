package com.cpq.task260904;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.service.backfill.QuoteBackfillService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-09 —— <b>AC-9（不再回填类型）</b>。
 *
 * <p>AC 原文（{@code 需求文档.md §3.1}）：
 * 「触发回填，查 <b>{@code material_bom_item}</b>（V6 老表）的新增行 …… 断言：
 *  ① 新增行的 {@code characteristic} <b>为空</b>（改动前会被写入 {@code __nodeType} 的中文值
 *  「材质/零件/外购件」，而该列现网值域是 {@code RECIPE/ASSEMBLY/OUTSOURCED} —— 写中文本身就是脏数据）；
 *  ② {@code ds_quote_material_bom.output_material_type} 不受影响」。
 *
 * <h3>🚨 这条 AC 自己点名的重言陷阱，本类怎么规避</h3>
 * <ul>
 *   <li>AC 原文警告 v1「查错了表」—— 断言 {@code ds_quote_material_bom} 新增行会恒成立。
 *       ⇒ 本类的<b>硬断言只落在 {@code material_bom_item}</b>，{@code ds_quote_material_bom} 只做附带观察。</li>
 *   <li>「characteristic 为空」在**没有新增行**时同样恒成立（0 行全为空）。
 *       ⇒ 先硬断言 {@code summary.addedRows == 1} 且真的查到那一行，<b>再</b>断言它的 characteristic。</li>
 *   <li>现网 {@code material_bom_item.characteristic} 的值域实测是
 *       {@code RECIPE(11102) / ASSEMBLY(51) / OUTSOURCED(1) / NULL(1)} —— <b>一个中文值都没有</b>。
 *       所以「全库没有中文 characteristic」这种全局断言是纯重言，本类不用它。</li>
 * </ul>
 *
 * <h3>为什么这条用例在改动前后行为不同（= 它真的在验东西）</h3>
 * 夹具喂进去的手工叶子带 {@code "__nodeType":"材质"}。改动前回填把该值写进 {@code characteristic}
 * ⇒ 落库是中文「材质」，本用例<b>红</b>；改动后不再回填类型 ⇒ 落库为空，本用例<b>绿</b>。
 *
 * <h3>零残留</h3>
 * 全程 {@code @TestTransaction}（回滚），不往共享库留任何行 —— 连 DELETE 都不需要。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-9 —— 回填不再把 __nodeType 写进 characteristic")
class BackfillCharacteristicAcTest extends Task260904Base {

    private static final String TAG = "T260904BF";

    @Inject
    QuoteBackfillService backfillService;

    private UUID newTreeBomItemComponent(String suffix) {
        Component c = new Component();
        c.name = TAG + "-树BOM-" + suffix;
        c.code = TAG + "-TREE-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[]";
        c.formulas = "[]";
        c.tabType = "BOM";
        c.bomRecursiveExpand = true;
        c.dataDriverPath = "$" + (TAG + "_tree_" + suffix).toLowerCase();
        c.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_tree_" + suffix).toLowerCase();
        // 🚨 回填的路由判据是「该组件驱动视图解析出的 primaryTable 是不是 material_bom_item」
        //    （需求文档 AC-9 原文点名 QuoteBackfillCollector 的这条判据）。
        //    视图必须真的 FROM material_bom_item，否则回填一行都不会写 ⇒ 本用例整体空跑。
        view.sqlTemplate =
                "SELECT\n"
                        + "  mbt.material_no AS hf_part_no,\n"
                        + "  mbt.seq_no AS 序号,\n"
                        + "  mbt.component_no AS 子料号,\n"
                        + "  mbt.composition_qty AS 数量,\n"
                        + "  mbt.issue_unit AS 单位\n"
                        + "FROM material_bom_item mbt\n"
                        + "WHERE mbt.system_type = 'QUOTE' AND mbt.customer_no = :customerCode AND mbt.is_current = true\n"
                        + "ORDER BY mbt.seq_no";
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    @Test
    @TestTransaction
    @DisplayName("AC-9：手工树叶子回填后，material_bom_item 新增行的 characteristic 不得是中文类型值")
    void ac9_backfillDoesNotWriteNodeTypeIntoCharacteristic() {
        // ── 前置数据（真实业务表，非空夹具）──
        Object customerRow = col("SELECT id FROM customer WHERE status='ACTIVE' ORDER BY created_at LIMIT 1")
                .stream().findFirst().orElse(null);
        assertNotNull(customerRow, "前置未满足：库里没有 ACTIVE 客户 ⇒ 造不出报价单夹具");
        UUID customerId = toUUID(customerRow);
        String customerNo = scalar("SELECT code FROM customer WHERE id = '" + customerId + "'");
        assertNotNull(customerNo, "前置：客户必须有 code（它就是 V6 表的 customer_no 维度）");

        UUID salesRepId = toUUID(col("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1").stream().findFirst().orElse(null));
        assertNotNull(salesRepId, "前置：admin 用户应存在");
        Object financeRow = col("SELECT id FROM \"user\" WHERE role='PRICING_MANAGER' AND status='ACTIVE' LIMIT 1")
                .stream().findFirst().orElse(null);
        assertNotNull(financeRow, "前置未满足：库里没有 ACTIVE 的 PRICING_MANAGER ⇒ 回填的操作人取不到。"
                + "🚫 本用例刻意不自己造这个用户 —— 造账号属于改变共享库的全局状态（testing.md §4.3）。");
        UUID finance = toUUID(financeRow);

        // 根料号与叶子料号都用本用例唯一生成的值 ⇒ 与任何存量数据零交集，回滚后零残留
        // 🚨 material_bom.customer_no / material_no 是 varchar(20)：料号必须 ≤20 字符，
        //    超长会以 "value too long for type character varying(20)" 的面目失败，看起来像业务缺陷。
        String rootMaterialNo = "T260904R" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String leafChild = "T260904L" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newTreeBomItemComponent("AC9");
        UUID quotationId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,"
                        + "tax_rate,tax_amount,created_at,updated_at) "
                        + "VALUES (:id,:qn,:cid,:n,:srid,'SUBMITTED',0,0,now(),now())")
                .setParameter("id", quotationId).setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                .setParameter("cid", customerId).setParameter("n", TAG + "-报价单")
                .setParameter("srid", salesRepId).executeUpdate();
        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO quotation_line_item (id,quotation_id,product_part_no_snapshot,sort_order,created_at) "
                        + "VALUES (:id,:qid,:pn,0,now())")
                .setParameter("id", lineItemId).setParameter("qid", quotationId)
                .setParameter("pn", rootMaterialNo).executeUpdate();

        // 手工树叶子：形状照 add-leaf 的产物（__manual + __nodeType）。
        // 🚨 __nodeType 刻意写「材质」中文值 —— 改动前回填会把它原样塞进 characteristic。
        String snapshotRows = "[{\"driverRow\":{\"material_no\":\"" + leafChild + "\"},\"__manual\":true,"
                + "\"__parentNo\":\"" + rootMaterialNo + "\",\"__nodeType\":\"材质\",\"__nodeId\":\""
                + rootMaterialNo + "/__manual_1\"}]";
        em.createNativeQuery("INSERT INTO quotation_line_component_data "
                        + "(id,line_item_id,component_id,tab_name,snapshot_rows,row_data,deleted_row_keys,sort_order,created_at) "
                        + "VALUES (:id,:lid,:cid,'BOM树',CAST(:sr AS jsonb),'[]'::jsonb,'[]'::jsonb,0,now())")
                .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId)
                .setParameter("cid", componentId).setParameter("sr", snapshotRows).executeUpdate();
        em.flush();

        long dsBefore = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no = '" + rootMaterialNo + "'");

        // ── 执行回填 ──
        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);

        // ① 🚨 非空守卫：先证明回填真的写了行，否则「characteristic 为空」是 0 行的重言
        assertEquals(1, summary.addedRows,
                "AC-9 前置：手工树叶子应被回填识别为 1 条新增行。实际 addedRows=" + summary.addedRows
                        + " ⇒ 一行都没写的话，后面『characteristic 为空』的断言是纯重言（四类假绿之「断言从未执行」）。");

        List<Object[]> written = rows("SELECT component_no, characteristic FROM material_bom_item "
                + "WHERE system_type='QUOTE' AND customer_no='" + customerNo + "' "
                + "AND material_no='" + rootMaterialNo + "' AND is_current = true");
        assertFalse(written.isEmpty(), "AC-9 前置：应能查到刚回填出来的 material_bom_item 行");
        assertEquals(1, written.size(), "AC-9 前置：应恰好 1 条新增行，实际=" + written.size());
        assertEquals(leafChild, String.valueOf(written.get(0)[0]), "AC-9 前置：新增行的 component_no 应是手工叶子的料号");

        // ② 本条 AC 的正主：characteristic 不得被写成 __nodeType 的中文值
        Object characteristic = written.get(0)[1];
        System.out.println("[AC-9] 回填出的行 component_no=" + written.get(0)[0] + " characteristic=" + characteristic);
        assertNull(characteristic,
                "AC-9①：回填不应再把 __nodeType 写进 characteristic —— 实际写入了「" + characteristic + "」。"
                        + "该列现网值域是 RECIPE/ASSEMBLY/OUTSOURCED，写中文本身就是脏数据。");

        // ③ 附带观察（🚫 不作硬判据 —— AC 原文自己点明这张表本就不会有新增行，断它是重言）
        long dsAfter = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no = '" + rootMaterialNo + "'");
        System.out.println("[AC-9②·观察] ds_quote_material_bom(material_no=" + rootMaterialNo + ") before=" + dsBefore
                + " after=" + dsAfter + "（回填从不写这张表，此处只留证据不作判据）");
        assertTrue(dsAfter == dsBefore,
                "AC-9②：ds_quote_material_bom 不应受回填影响（before=" + dsBefore + " after=" + dsAfter + "）");
    }
}
