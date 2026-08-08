package com.cpq.configure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * task-0806 阶段③a 等价护栏（<b>非破坏性</b>，{@link PersistWholeBatchEquivTest} 同款设计）。
 *
 * <p><b>背景</b>：报价编辑路径（{@code CardSnapshotService#materializeWholeLineRowData}，被
 * {@code editCardValue} 单元格失焦同步与 {@code materializeAndProject} 树删除/恢复重算两处调用）
 * 此前固定调用 {@link ConfigureSnapshotService#materializeLineRowData(UUID, JsonNode, Map, Map, Map, Map)}
 * 6 参重载，该重载把 {@code batchWriteEnabled} 硬编码 {@code false} → 每个页签各一次
 * {@link ConfigureSnapshotService#writeRowData}（{@code REQUIRES_NEW} 独立事务），8 页签 = 8 次独立
 * 事务提交（生产态实测占单次编辑中位耗时约 234ms）。首存路径早就有批量写
 * {@link ConfigureSnapshotService#writeRowDataBatch}（N×M → N×1，同一 {@code REQUIRES_NEW} 事务内
 * 一条多值 {@code UPDATE…FROM(VALUES…)} + 未命中再一条多值 {@code INSERT}），编辑路径只是历史上没接上。
 * 阶段③a 把编辑路径的调用点改为显式传参 {@code editBatchWrite}（kill switch
 * {@code cpq.editpath-batch-write}，默认 {@code true}），选择 {@code writeRowDataBatch} 还是逐组件
 * {@code writeRowData}。
 *
 * <p><b>本测试证明什么</b>：{@code writeRowDataBatch}（batch=true 分支实际调用的方法）与逐组件
 * {@code writeRowData}（batch=false 分支、原行为实际调用的方法）落库内容<b>逐位一致</b>——
 * 读一份真实报价单当前的 {@code row_data} 内容 → <b>原样写回</b>（不重新计算，不调
 * {@code materializeLineRowData}） → DB 内容必须不变。两路写的都是读到的同一份内容，故对正确实现
 * 是恒等回写；若批量写的元组配对（{@code line_item_id, component_id}）或 JSON 序列化有 bug，内容会变，
 * 断言即可捕获。
 *
 * <p><b>为什么不直接调用 {@code materializeLineRowData(...,batchWriteEnabled)} 跑两遍来对比</b>：
 * 那需要传 {@code editRowsByComp}/{@code rowKeyFieldsByComp} 等参数并重新跑一遍公式引擎计算，
 * 会用"配置态口径"（editRows 全空）覆盖掉该行当前可能存在的编辑态 {@code row_data}，对夹具产生持久
 * 副作用（K12：拿活夹具做验证会产生副作用）。本测试改为直接对 {@code writeRowDataBatch}/
 * {@code writeRowData} 这两个底层写方法做 verbatim 往返（读什么写什么），是 {@code batchWriteEnabled}
 * 开关实际引入的全部行为差异所在，且天然非破坏性。
 *
 * <p><b>夹具选择</b>：测试库 {@code cpq_db}（{@code mvnw test} 走这个库，与开发库
 * {@code cpq_db_0724} 不是同一个库，见 {@code CLAUDE.md}）里实测 {@code row_data} 覆盖面最大的单据
 * —— {@code QT-20260716-2046}（49 个组件有 row_data，共 80 行）。
 * <b>禁止用 {@code Assumptions.assumeTrue} 兜底"夹具缺失就跳过"</b>：那样会让这条护栏在夹具漂移后
 * 静默失效（"缺数据导致的『未覆盖』不等于『已实现』"，见项目历史教训 cpq-unverifiable-feature-masks-gap）；
 * 改用硬断言 {@link #assertFalse}，夹具没了必须让测试<b>失败并吵出来</b>，而不是悄悄跳过。
 *
 * <p><b>已知覆盖缺口</b>：本测试用真实夹具的 {@code row_data} 原样往返，该夹具的行均已存在
 * （UPDATE 命中），故只覆盖了 {@link ConfigureSnapshotService#writeRowDataBatch} 的
 * <b>UPDATE 分支</b>，未覆盖"未命中批量 INSERT"那条分支（需要构造 {@code row_data IS NULL} 的行，
 * 代价大且会污染夹具，故不在本测试范围）。互补证据：生产态用打包 jar 对真实 8 页签单据做过
 * batch=true / batch=false 完整重算链路（含 INSERT/UPDATE 混合）A/B，7 个组件
 * {@code md5(row_data::text)} 逐个全等（见 test-report.md）。
 */
@QuarkusTest
class RowDataBatchWriteEquivTest {

    @Inject ConfigureSnapshotService snapshotService;
    @Inject EntityManager em;

    /** QT-20260716-2046 —— 测试库 cpq_db 里 row_data 覆盖面最大的夹具（49 组件/80 行）。 */
    private static final UUID FIXTURE_QID = UUID.fromString("4cd85181-073b-4935-adf3-09557808d57c");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 未加 {@code @Transactional}：两个被测方法（{@code writeRowDataBatch}/{@code writeRowData}）
     * 各自声明 {@code REQUIRES_NEW}，本方法即便挂 {@code @Transactional} 也因同实例自调用
     * （JUnit 直接 new 测试类反射调用，CDI 拦截器不介入）不会真的开启外层事务 —— 挂一个不生效的
     * 注解只会误导后人以为整个测试跑在一个（可能 600s 超时的）事务里。两个 REQUIRES_NEW 方法各自
     * 独立提交，本测试不需要、也不依赖外层事务。
     */
    @Test
    void rockwell() throws Exception {
        UUID qid = FIXTURE_QID;
        Map<UUID, Map<UUID, ArrayNode>> byLine = readRowData(qid);
        assertFalse(byLine.isEmpty(),
                "夹具 " + qid + " 无 row_data —— 测试库夹具失效，请更新 FIXTURE_QID（不允许静默跳过）");

        String baseline = contentMd5(qid);

        // ① 整行一次批量写回(应恒等) —— cpq.editpath-batch-write=true 分支实际调用的方法
        for (Map.Entry<UUID, Map<UUID, ArrayNode>> e : byLine.entrySet()) {
            snapshotService.writeRowDataBatch(e.getKey(), e.getValue());
        }
        String afterBatch = contentMd5(qid);

        // ② 逐组件写回(应恒等) —— cpq.editpath-batch-write=false 分支(原行为)实际调用的方法
        for (Map.Entry<UUID, Map<UUID, ArrayNode>> e : byLine.entrySet()) {
            for (Map.Entry<UUID, ArrayNode> ce : e.getValue().entrySet()) {
                snapshotService.writeRowData(e.getKey(), ce.getKey(), MAPPER.writeValueAsString(ce.getValue()));
            }
        }
        String afterPerComp = contentMd5(qid);

        System.out.printf("[row_data-batch-equiv] qid=%s baseline=%s batch=%s perComp=%s%n",
                qid, baseline, afterBatch, afterPerComp);
        assertEquals(baseline, afterBatch,
                "batchWriteEnabled=true(writeRowDataBatch)写回必须保持 row_data 内容不变(=逐组件写等价)");
        assertEquals(baseline, afterPerComp,
                "batchWriteEnabled=false(writeRowData 逐组件,原行为)写回必须保持内容不变(对照)");
    }

    /** 读当前 row_data(仅非 NULL、且为 JSON 数组的行 —— 与 writeRowDataBatch/writeRowData 的写入形态一致)。 */
    private Map<UUID, Map<UUID, ArrayNode>> readRowData(UUID qid) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT d.line_item_id, d.component_id, d.row_data::text " +
            "FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
            "WHERE li.quotation_id = :q AND d.row_data IS NOT NULL ORDER BY d.line_item_id, d.component_id")
            .setParameter("q", qid).getResultList();
        Map<UUID, Map<UUID, ArrayNode>> byLine = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
            UUID cid = (r[1] instanceof UUID u) ? u : UUID.fromString(r[1].toString());
            String json = r[2] == null ? null : r[2].toString();
            if (json == null) continue;
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) continue; // 防御：row_data 理论恒为数组，非数组行跳过不纳入等价对比
            byLine.computeIfAbsent(lid, k -> new LinkedHashMap<>()).put(cid, (ArrayNode) n);
        }
        return byLine;
    }

    private String contentMd5(UUID qid) {
        Object r = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(" +
            "  d.line_item_id::text || '|' || d.component_id::text || '|' || " +
            "  COALESCE(d.row_data::text,'∅'), E'\\n' " +
            "  ORDER BY d.line_item_id, d.component_id), '')) " +
            "FROM quotation_line_component_data d " +
            "JOIN quotation_line_item li ON li.id = d.line_item_id WHERE li.quotation_id = :q")
            .setParameter("q", qid).getSingleResult();
        return r == null ? "" : r.toString();
    }
}
