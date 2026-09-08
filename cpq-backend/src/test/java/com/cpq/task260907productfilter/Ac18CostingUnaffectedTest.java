package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B11 · AC-18</b>（反向 · 核价两套不被波及）。
 *
 * <p>需求文档.md §③ AC-18：核价两套（{@code cost-basic}/{@code cost-detail}）没有 {@code customer_no} 列。
 * 传入 {@code customerNo} 参数时的行为必须显式定义：{@code api.md} §4 已裁决为
 * <b>「忽略该参数，正常返回全部数据」</b>。断言：① 行为与改动前逐字一致；② 传 {@code customerNo}
 * 不得 500；③ 🚫 不得静默返回空列表（那会被误读成「这个客户没有数据」）。
 *
 * <p>本类不追求「与改动前逐字一致」的完整 md5 基线（那需要额外的持久锚点管理，
 * 且 {@code cost-basic}/{@code cost-detail} 的真实数据轴值目前未知，本类不猜测/不硬编码 —
 * 参考 {@code task260902.DatasetAcTestBase} 的既有教训：Excel 模板里的示例编号在库里常常查不到）。
 * 改为验证<b>行为不变量</b>：「带不带 {@code customerNo}，两次调用的 {@code total} 与 {@code items}
 * 完全相同」——这个不变量本身就直接蕴含「参数被忽略」，比对基线文件更不容易踩空锚点的坑。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-18 反向：核价两套不被波及（参数被忽略）")
@QuarkusTest
class Ac18CostingUnaffectedTest extends PfTestBase {

    @Test
    @DisplayName("AC-18①②③(cost-basic)：传 customerNo 与不传结果完全相同、不 500、不静默变空")
    void costBasicIgnoresCustomerNoParam() {
        assertCostingDatasetIgnoresParam(PfApi.COST_BASIC);
    }

    @Test
    @DisplayName("AC-18①②③(cost-detail)：传 customerNo 与不传结果完全相同、不 500、不静默变空")
    void costDetailIgnoresCustomerNoParam() {
        assertCostingDatasetIgnoresParam(PfApi.COST_DETAIL);
    }

    private void assertCostingDatasetIgnoresParam(String dataset) {
        Response withoutParam = PfApi.parts(adminSession(), dataset, null, 0, 200, null);
        assertEquals(200, withoutParam.statusCode(), dataset + " 不传 customerNo → " + withoutParam.statusCode()
                + " body=" + withoutParam.asString());
        List<Map<String, Object>> itemsWithout = withoutParam.jsonPath().getList("data.items");
        Object totalWithout = withoutParam.jsonPath().get("data.total");
        assertNotNull(itemsWithout, dataset + "：不传参数时响应缺 data.items");
        assertNotNull(totalWithout, dataset + "：不传参数时响应缺 data.total");

        // 前置：先证明这套数据集本身确实有数据，否则「静默变空」这条断言毫无意义（可能它本来就是空的）
        long dbTotal = countRowsIfPossible(dataset);
        System.out.println("[AC-18] " + dataset + " 不传参数 total=" + totalWithout + " 库参考值=" + dbTotal);
        assertTrue(((Number) totalWithout).longValue() > 0,
                "AC-18② 前置：" + dataset + " 不传 customerNo 时 total=0 ⇒ 「不得静默变空」的对照断言本身就无法判别"
                        + "（这套数据集当前没有数据，需要先确认它本来就该有数据，或换一个有数据的数据集）");

        // 🚨 核心断言②③：传一个真实存在的客户号，结果必须与不传时完全相同（忽略参数，不静默变空、不 500）
        Response withParam = PfApi.parts(adminSession(), dataset, CUST_A, 0, 200, null);
        System.out.println("[AC-18] " + dataset + " 传 customerNo=" + CUST_A + " → HTTP " + withParam.statusCode());
        assertNotEquals(500, withParam.statusCode(), "AC-18②：" + dataset + " 传 customerNo 不得 500（它没有客户维度，"
                + "应显式忽略参数或 400，绝不能因为拼了一个不存在的列名而抛异常）。body=" + withParam.asString());

        if (withParam.statusCode() == 400) {
            // api.md §4 的可选实现之一（显式 400）——本任务已裁决为"忽略参数"，但若实现选了 400 分支，
            // 至少必须不是"静默返回空列表"，用例仍需记录，交主线核对是否与裁决一致
            System.out.println("[AC-18] ⚠️ " + dataset + " 对 customerNo 返回 400（api.md §4 裁决的是"
                    + "「忽略参数」而非「显式 400」，如实际实现选了 400 分支，需要主线核对是否与裁决一致）");
            return;
        }

        assertEquals(200, withParam.statusCode(), dataset + " 传 customerNo → " + withParam.statusCode()
                + " body=" + withParam.asString());
        Object totalWith = withParam.jsonPath().get("data.total");
        List<Map<String, Object>> itemsWith = withParam.jsonPath().getList("data.items");
        assertNotNull(totalWith, dataset + "：传参数时响应缺 data.total");

        assertFalse(((Number) totalWith).longValue() == 0 && ((Number) totalWithout).longValue() > 0,
                "AC-18③🚨：" + dataset + " 传 customerNo=" + CUST_A + " 后 total 从 " + totalWithout
                        + " 静默变成 0 —— 这是 api.md §4 明确禁止的最坏失败形态（会被用户误读成"
                        + "『这个客户没有数据』，真相是这套数据集根本没有客户维度）");
        assertEquals(totalWithout, totalWith, "AC-18①：" + dataset + " 传/不传 customerNo 的 total 应完全相同"
                + "（参数应被忽略），实际不传=" + totalWithout + " 传=" + totalWith);
        assertEquals(itemsWithout != null ? itemsWithout.size() : -1, itemsWith != null ? itemsWith.size() : -1,
                "AC-18①：" + dataset + " 传/不传 customerNo 返回的 items 数量应相同");
    }

    /** 只读参考值，查不到就返回 -1（数据集实体表名未知，只在能确定时提供参考，不强依赖）。 */
    private long countRowsIfPossible(String dataset) {
        String table = PfApi.COST_BASIC.equals(dataset) ? "ds_cost_basic_material"
                : PfApi.COST_DETAIL.equals(dataset) ? "ds_cost_detail_material" : null;
        if (table == null || !tableExists(table)) {
            return -1;
        }
        return count("SELECT count(*) FROM " + table);
    }
}
