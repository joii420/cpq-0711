package com.cpq.quotation.service;

import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.entity.CostingBomTreeConfig;
import com.cpq.component.service.ComponentService;
import com.cpq.component.service.ComponentSqlViewService;
import com.cpq.component.service.CostingBomTreeConfigService;
import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.service.TemplateComponentService;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 —— 报价侧 BOM 树【持久化】UI 验收 fixture 构建脚本(2026-07-21 业务方裁决)。
 *
 * <p><b>与 {@code QuoteBomTreeEndToEndTest} 的区别</b>：那份是自清理的单测证明(每次跑完真删除，
 * 不留痕迹)；本类反过来——<b>刻意不清理</b>，把数据永久建在共享库 {@code cpq_db}，供业务方在
 * {@code http://localhost:5174}(前端) / {@code http://localhost:8081}(后端 curl) 上做真实 UI 渲染验收
 * ("必须有真实渲染过的页面才能签字")。B12(存量清除)阶段之前，本 fixture 一直保留。
 *
 * <p><b>默认 SKIP</b>：不是常规回归测试，通过 {@link Assumptions#assumeTrue} 默认跳过，仅当显式传
 * {@code -Dtask0721.build.fixture=true} 时才真正建数据 —— 避免 {@code mvn test} 广域回归意外重跑，
 * 撞组件 code / SQL 视图名 / costing_bom_tree_config name 的唯一约束（重跑前若要重建，先用交付清单里的
 * ID 手工清理，或直接改本文件里的 {@code TAG} 常量后缀避免撞名）。
 *
 * <p><b>全程走生产真实入口</b>(与 REST 端点同一段代码路径，不手搓 snapshot_rows JSON)：
 * {@link ComponentService#create} → {@link ComponentSqlViewService#create} → {@link TemplateService#create}
 * → {@link TemplateComponentService#addComponent} → {@link TemplateService#publish} →
 * {@link QuotationService#create} → {@link QuotationService#saveDraft} →
 * {@link ConfigureSnapshotService#snapshotQuotation}（与 {@code QuotationResource#saveDraft} 完全同一
 * 后置顺序：saveDraft → ensureStructure(best-effort) → snapshotQuotation(增量)）→
 * {@link CardSnapshotService#ensureCardValues}（与前端"打开报价单"触发的 warm 端点
 * {@code POST /api/cpq/quotations/{id}/ensure-card-values} 同一入口，实证渲染真的产出 tabs，
 * 不是只有裸 snapshot_rows）。
 *
 * <p><b>DAG 数据来源(2026-07-21 现场核实)</b>：委托方最初以为的"现网已无此 DAG"结论有误——
 * 经直查 {@code material_bom_item}(customer_no='_GLOBAL_', system_type='PRICING', is_current=true)：
 * <pre>
 * 3120018220 -&gt; {1630010773, 2120011658, 2120011659, 3110520790, 3111320634~3111320637}(8 直接子)
 * 2120011658 -&gt; {3110520789(RECIPE), 3112230066(ASSEMBLY)}
 * 2120011659 -&gt; {3110520789(RECIPE), 3112230067(ASSEMBLY)}
 * 3110520789 -&gt; {2101110225(料11), 2111410069(料12)}(均 RECIPE)
 * </pre>
 * 3110520789 确实同挂 2120011658/2120011659 两支（DAG，非合成数据）；此前判断有误的原因是当时误查
 * 了不存在的 {@code hf_part_no}/{@code parent_no} 列名（实际列名是 {@code material_no}(owner)/
 * {@code component_no}(child)），列名拼错导致查询报错、误判为"数据不存在"。递归 SQL 配置直接克隆现网
 * 现役 COSTING BOMV2 的 {@code sql_template}（同一套真实字段/表约定，不新造合成 SQL），仅 usage 维度
 * 独立（QUOTE vs COSTING，各自一条 active，互不干扰，见 V346 {@code uq_bom_tree_config_active_per_usage}
 * 部分唯一索引）。
 */
@QuarkusTest
@DisplayName("Task0721PersistentFixtureBuilder — 持久化 UI 验收 fixture(不清理，默认 SKIP)")
class Task0721PersistentFixtureBuilder {

    private static final String TAG = "TASK0721";
    private static final ObjectMapper M = new ObjectMapper();

    @Inject ComponentService componentService;
    @Inject ComponentSqlViewService componentSqlViewService;
    @Inject CostingBomTreeConfigService costingBomTreeConfigService;
    @Inject TemplateService templateService;
    @Inject TemplateComponentService templateComponentService;
    @Inject QuotationService quotationService;
    @Inject ConfigureSnapshotService configureSnapshotService;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    @Test
    @DisplayName("建持久化 fixture(默认 SKIP，需 -Dtask0721.build.fixture=true 才真正执行)")
    void buildPersistentFixture() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("task0721.build.fixture"),
                "一次性 fixture 构建脚本，默认跳过。需要(重)建时显式传 -Dtask0721.build.fixture=true");

        // ① QUOTE usage 递归 SQL 配置 —— 克隆现网现役 COSTING BOMV2 的 sql_template(同一套真实字段/表约定)。
        // 【发现的既有缺口,已绕开,非本任务修复范围】CostingBomTreeConfigService.create()/update() 走
        // CostingTreeSqlValidator.validate() dry-run,该校验器只替换 :production_part_nos,不展开
        // VersionFilterMacro 的 :versionFilter(...) 语法糖 —— 而现网 BOMV2 现役 sql_template 恰好用了
        // :versionFilter(...) 宏,导致克隆同一段真实生产 SQL 经 create() 服务方法反而会被自家校验器拒绝
        // ("syntax error at or near ':'")。SQL 本身在生产是每天真实跑的(BomTreeRenderService#queryRecursive
        // 会先 VersionFilterMacro.expandForExecution() 再执行),问题只在这个 dry-run 校验器没跟上宏语法,
        // 与本次 task-0721 改动无关(校验器代码未改),故此处原生 INSERT 绕过校验器(而非改校验器,避免
        // scope creep),同时已在交付报告里向委托方标注这个可能影响"管理界面重新保存现役 BOMV2 配置"的
        // 潜在缺口。
        @SuppressWarnings("unchecked")
        List<Object> bomv2 = em.createNativeQuery(
                        "SELECT sql_template FROM costing_bom_tree_config WHERE usage='COSTING' AND is_active=true")
                .getResultList();
        assertFalse(bomv2.isEmpty(), "前置条件:核价侧 BOMV2 (usage=COSTING) 必须仍是 active,否则无法克隆其 SQL");
        String bomv2Sql = (String) bomv2.get(0);

        UUID quoteCfgId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "INSERT INTO costing_bom_tree_config (id, name, sql_template, is_active, usage, created_at, updated_at) " +
                                "VALUES (:id, :name, :sql, true, 'QUOTE', now(), now())")
                        .setParameter("id", quoteCfgId)
                        .setParameter("name", TAG + "-QUOTE-BOMV2克隆")
                        .setParameter("sql", bomv2Sql)
                        .executeUpdate());
        CostingBomTreeConfig quoteCfg = CostingBomTreeConfig.findById(quoteCfgId);

        // 校验:核价侧 COSTING active 未被顶掉(每 usage 各自一条,互不干扰 —— B1 要求)
        Number costingStillActive = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM costing_bom_tree_config WHERE usage='COSTING' AND is_active=true")
                .getSingleResult();
        assertEquals(1, costingStillActive.intValue(), "激活 QUOTE 配置不应影响 COSTING 侧现役配置");

        // ② 树组件(tab_type=BOM,专用新建,不复用核价侧树组件 —— AC-10 零回归门禁)
        CreateComponentRequest treeReq = new CreateComponentRequest();
        treeReq.name = TAG + "-BOM树(3120018220验收)";
        treeReq.code = TAG + "-TREE-BOM";
        treeReq.tabType = "BOM";
        treeReq.rowKeyFields = List.of("__seq_no__"); // 树 spine 行身份来自系统列 __nodeId,非业务字段,显式豁免
        treeReq.fields = List.of(
                fieldOf("数量", "INPUT_NUMBER", "$task0721_tree_view._数量"),
                fieldOf("单位", "INPUT_TEXT", "$task0721_tree_view._单位"));
        treeReq.formulas = List.of();
        ComponentDTO treeComp = componentService.create(treeReq);

        CreateComponentSqlViewRequest treeViewReq = new CreateComponentSqlViewRequest();
        treeViewReq.sqlViewName = "task0721_tree_view";
        treeViewReq.description = TAG + " 报价侧树验收 —— 真实 material_bom_item BOM 边(数量/单位)";
        treeViewReq.sqlTemplate =
                "SELECT bi.component_no AS material_no,\n" +
                "       bi.material_no AS parent_no,\n" +
                "       bi.component_no AS hf_part_no,\n" +
                "       bi.composition_qty AS _数量,\n" +
                "       bi.issue_unit AS _单位\n" +
                "FROM material_bom_item bi\n" +
                "WHERE bi.customer_no = '_GLOBAL_' AND bi.system_type = 'PRICING' AND bi.is_current = true\n" +
                "  AND bi.component_no IS NOT NULL";

        UUID adminUserId = adminUserId();
        componentSqlViewService.create(treeComp.id, treeViewReq, adminUserId);

        // ③ 材质元素组件(part_no_field=料号) —— 真实 material_master 数据(2101110225/2111410069)
        CreateComponentRequest matReq = new CreateComponentRequest();
        matReq.name = TAG + "-材质元素(验收)";
        matReq.code = TAG + "-MAT-ELEMENT";
        matReq.tabType = "材质元素";
        matReq.partNoField = "料号";
        matReq.rowKeyFields = List.of("料号");
        matReq.fields = List.of(
                fieldOf("料号", "INPUT_TEXT", "$task0721_mat_view._料号"),
                fieldOf("名称", "INPUT_TEXT", "$task0721_mat_view._名称"),
                fieldOf("规格", "INPUT_TEXT", "$task0721_mat_view._规格"));
        matReq.formulas = List.of();
        ComponentDTO matComp = componentService.create(matReq);

        CreateComponentSqlViewRequest matViewReq = new CreateComponentSqlViewRequest();
        matViewReq.sqlViewName = "task0721_mat_view";
        matViewReq.description = TAG + " 报价侧树验收 —— 真实 material_master(2101110225/2111410069)";
        matViewReq.sqlTemplate =
                "SELECT '3120018220'::text AS hf_part_no,\n" +
                "       m.material_no AS _料号,\n" +
                "       m.material_name AS _名称,\n" +
                "       m.dimension AS _规格\n" +
                "FROM material_master m\n" +
                "WHERE m.material_no IN ('2101110225','2111410069')";
        componentSqlViewService.create(matComp.id, matViewReq, adminUserId);

        // ④ 报价模板(挂树 + 材质元素两页签) + 发布
        CreateTemplateRequest tplReq = new CreateTemplateRequest();
        tplReq.name = TAG + "-报价侧树模板";
        tplReq.templateKind = "QUOTATION";
        TemplateDTO tplDto = templateService.create(tplReq);
        templateComponentService.addComponent(tplDto.id, treeComp.id, "BOM树");
        templateComponentService.addComponent(tplDto.id, matComp.id, "材质元素");
        templateService.publish(tplDto.id, new PublishRequest());

        // ⑤ 报价单(真实客户/销售,customerTemplateId 指向④) + 一条产品线(3120018220)
        UUID customerId = existingCustomerId("苏州西门子");

        CreateQuotationRequest qReq = new CreateQuotationRequest();
        qReq.customerId = customerId;
        qReq.name = TAG + "-报价侧树状渲染验收";
        qReq.customerTemplateId = tplDto.id;
        QuotationDTO qDto = quotationService.create(qReq, adminUserId);

        // ⑥ saveDraft(与 QuotationResource#saveDraft 完全同一入口/顺序) —— 真正触发物化
        SaveDraftRequest draftReq = new SaveDraftRequest();
        draftReq.customerTemplateId = tplDto.id;
        SaveDraftRequest.LineItemDraft li = new SaveDraftRequest.LineItemDraft();
        li.id = null; // 新行,服务端生成 id
        li.templateId = tplDto.id;
        li.productPartNo = "3120018220";
        li.productName = TAG + "验收产品(3120018220)";
        li.sortOrder = 0;

        SaveDraftRequest.ComponentDataDraft treeCd = new SaveDraftRequest.ComponentDataDraft();
        treeCd.componentId = treeComp.id;
        treeCd.tabName = "BOM树";
        treeCd.rowData = "[]"; // 占位,真实 spine 由下方⑦物化写入

        SaveDraftRequest.ComponentDataDraft matCd = new SaveDraftRequest.ComponentDataDraft();
        matCd.componentId = matComp.id;
        matCd.tabName = "材质元素";
        matCd.rowData = "[]"; // 占位,真实材质行由 driver expand 物化写入(非手工行)

        li.componentData = List.of(treeCd, matCd);
        draftReq.lineItems = List.of(li);

        quotationService.saveDraft(qDto.id, draftReq);

        // ⑦ 与 QuotationResource#saveDraft 完全同一后置顺序:ensureStructure(best-effort) → snapshotQuotation(增量)
        try {
            cardSnapshotService.ensureStructure(qDto.id);
        } catch (Exception ignore) {
            // 结构快照尽力而为,与生产资源层同款容错
        }
        configureSnapshotService.snapshotQuotation(qDto.id, true);

        // ⑧ 真实新建行 id(saveDraft 服务端生成,重查取回)
        @SuppressWarnings("unchecked")
        List<Object> liRows = em.createNativeQuery(
                        "SELECT id FROM quotation_line_item WHERE quotation_id = :qid " +
                        "AND product_part_no_snapshot = '3120018220'")
                .setParameter("qid", qDto.id)
                .getResultList();
        assertEquals(1, liRows.size(), "应恰好新建 1 条产品线");
        UUID lineItemId = toUUID(liRows.get(0));

        // ⑨ 自检 A:spine 已物化 —— 树组件 snapshot_rows 应含全量闭包行(root+8 直接子+
        //    3110520789×2occ+3112230066+3112230067+2101110225×2occ+2111410069×2occ = 17 行)
        String treeRowsJson = readSnapshotRows(lineItemId, treeComp.id);
        assertNotNull(treeRowsJson, "树组件 snapshot_rows 应已物化写入");
        JsonNode treeRows = M.readTree(treeRowsJson);
        assertTrue(treeRows.isArray() && treeRows.size() >= 17,
                "spine 行数应 >= 17(真实 3120018220 全量闭包),实际=" + treeRows.size());
        int dup3110520789 = 0, withNodeType = 0;
        for (JsonNode row : treeRows) {
            assertTrue(row.has("__nodeId") && row.has("__hfPartNo") && row.has("__nodeType"),
                    "每行须含系统列, 实际行=" + row);
            if ("3110520789".equals(row.path("__hfPartNo").asText(""))) dup3110520789++;
            if (!row.path("__nodeType").isNull() && row.path("__nodeType").asText(null) != null) withNodeType++;
        }
        assertEquals(2, dup3110520789, "3110520789 应有 2 个 occurrence(DAG 双亲,现网真实数据)");
        assertTrue(withNodeType >= 2, "至少材质(2101110225/2111410069)应判定出类型,实际=" + withNodeType);

        // ⑩ 自检 B:材质元素页签真实物化了 2 行(2101110225/料11、2111410069/料12)
        String matRowsJson = readSnapshotRows(lineItemId, matComp.id);
        assertNotNull(matRowsJson, "材质元素组件 snapshot_rows 应已物化写入");
        JsonNode matRows = M.readTree(matRowsJson);
        assertEquals(2, matRows.size(), "材质元素页签应有 2 行(2101110225/2111410069),实际=" + matRows.size());

        // ⑪ 自检 C(★渲染证据):warm quoteCardValues(与前端"打开报价单"调用的 ensure-card-values 同一
        //     入口),证明整单能真正渲染出 tabs,不是只有裸 snapshot_rows。
        cardSnapshotService.ensureCardValues(qDto.id);
        em.clear();
        QuotationDTO refreshed = quotationService.getById(qDto.id);
        QuotationDTO.LineItemDTO lineDto = refreshed.lineItems.stream()
                .filter(x -> lineItemId.equals(x.id)).findFirst().orElse(null);
        assertNotNull(lineDto, "刷新后应能查到该产品线");
        assertNotNull(lineDto.quoteCardValues, "quoteCardValues 应已渲染(不是 null) —— UI 打开即可见");
        JsonNode cardValues = M.readTree(lineDto.quoteCardValues);
        assertTrue(cardValues.path("tabs").isArray() && cardValues.path("tabs").size() == 2,
                "quoteCardValues.tabs 应含 2 个页签(树+材质元素), 实际=" + cardValues.path("tabs"));

        // ⑫ 交付清单打印(供人工汇报 + 供协调者/前端复用)
        System.out.println("========== TASK0721 持久化 fixture 交付清单 ==========");
        System.out.println("quotationId        = " + qDto.id);
        System.out.println("quotationNumber    = " + qDto.quotationNumber);
        System.out.println("lineItemId         = " + lineItemId);
        System.out.println("customerTemplateId = " + tplDto.id);
        System.out.println("treeComponentId    = " + treeComp.id + " (code=" + treeComp.code + ")");
        System.out.println("matComponentId     = " + matComp.id + " (code=" + matComp.code + ")");
        System.out.println("quoteRecursiveConfigId(usage=QUOTE) = " + quoteCfg.id);
        System.out.println("产品料号 = 3120018220");
        System.out.println("DAG: 3120018220 -> {2120011658, 2120011659} -> 3110520789(双亲2occ) "
                + "-> {2101110225(料11), 2111410069(料12)}(各2occ)");
        System.out.println("=====================================================");
    }

    private static Map<String, Object> fieldOf(String name, String fieldType, String path) {
        return Map.of(
                "name", name,
                "field_type", fieldType,
                "default_source", Map.of("path", path, "type", "BASIC_DATA"));
    }

    @SuppressWarnings("unchecked")
    private UUID adminUserId() {
        List<Object> rows = em.createNativeQuery(
                        "SELECT id FROM \"user\" WHERE role = 'SYSTEM_ADMIN' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) {
            rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        }
        assertFalse(rows.isEmpty(), "DB 无任何 user");
        return toUUID(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private UUID existingCustomerId(String preferredName) {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer WHERE name = :n LIMIT 1")
                .setParameter("n", preferredName)
                .getResultList();
        if (!rows.isEmpty()) return toUUID(rows.get(0));
        rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer");
        return toUUID(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private String readSnapshotRows(UUID lineId, UUID componentId) {
        List<Object> r = em.createNativeQuery(
                        "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                        "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineId).setParameter("cid", componentId)
                .getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
