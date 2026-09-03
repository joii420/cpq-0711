package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.SaveDraftRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-18（AC-22 / AC-23，B-7 迁移：清理重复行 + 唯一约束）。
 *
 * <p><b>验的是什么</b>：
 * <ul>
 *   <li>AC-22：迁移应用后，{@code quotation_line_component_data} 里不应再存在
 *       {@code (line_item_id, component_id)} 重复组；唯一约束
 *       {@code uq_qlcd_line_component} 应存在。</li>
 *   <li>AC-23（反向·约束不误伤）：正常 {@code saveDraft} 保存流程连续跑 3 次，不应触发该唯一约束冲突
 *       ——证明约束键选对了，不会自己撞自己（UPSERT/全删全建两条路径都不会对同一
 *       (line_item_id, component_id) 重复插入两条活跃记录）。</li>
 * </ul>
 *
 * <p>⚠️ <b>已知限制（写入本报告，不隐藏）</b>：{@code 问题说明.md} §4「存量脏数据」提到的具体案例
 * （单 {@code QT-20260807-0145}、lineItem {@code 76c33527-...}，2026-08-29 主线实测修正：
 * 14 行原始数据 − 6 行重复 = 应剩 <b>8</b> 条，其中 <b>1</b> 条 {@code component_id=4a193e48-...}
 * 本就不在重复组内、{@code tab_name} 原生为空，迁移不应也没有动它——不是最初文档推算的 7 条全非空）
 * 是在 <b>dev 库 {@code cpq_db_0724}</b> 上实测的数据，本测试跑在 {@code mvnw test} 走的
 * {@code test} profile（{@code cpq_db}）上，两库不是同一个库（{@code CLAUDE.md} profile 表），
 * 该具体 lineItem 大概率不存在于测试库。因此该逐行核对<b>用 Assumptions 守卫</b>，只在这条数据
 * 恰好也存在于当前库时才断言其细节；该案例的权威核对仍需主线在 dev 库人工执行（见测试报告
 * "需人工执行"一节）。全库级判据（重复组=0、约束存在）不受此限制，是硬断言。</li>
 */
@QuarkusTest
@DisplayName("SaveDraftDuplicateComponentDataMigrationTest — repair-260829 T-18(AC-22/AC-23) B-7迁移")
class SaveDraftDuplicateComponentDataMigrationTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");
    private static final UUID KNOWN_DEV_LINE_ITEM_ID =
            UUID.fromString("76c33527-12a7-4610-a5ae-6d3fc83d9187");

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private UUID anyCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer,无法建 fixture");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @Transactional
    Component buildPlainComponent(String tag) {
        Component c = new Component();
        c.name = "T18测试-" + tag;
        c.code = "T18-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        c.fields = "[{\"name\":\"值\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.persist();
        componentIds.add(c.id);
        return c;
    }

    @Transactional
    UUID createDraftQuotationWithLine(UUID lineItemId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
                                "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                                "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
                                "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
                .setParameter("id", qid)
                .setParameter("num", "TEST-T18-MIGRATION-" + System.nanoTime())
                .setParameter("cid", anyCustomerId())
                .setParameter("sid", TEST_USER_ID)
                .setParameter("name", "repair-260829 T-18 迁移+连续保存")
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO quotation_line_item (id, quotation_id, sort_order, created_at) " +
                                "VALUES (:id, :qid, 0, NOW())")
                .setParameter("id", lineItemId)
                .setParameter("qid", qid)
                .executeUpdate();
        quotationIds.add(qid);
        return qid;
    }

    // repair-260829 事务修复：DB 写操作(DELETE)必须在自己的活跃事务里执行，@AfterEach 方法本身
    // 没有事务上下文；沿用 SaveDraftZeroLineItemsTest 已验证可用的手法——把实际的 DB 操作
    // 拆到单独的、非 private 的 @Transactional 辅助方法，@AfterEach 只负责循环 + 兜底 try/catch。
    @Transactional
    void deleteQuotationCascade(UUID qid) {
        em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :qid)")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                .setParameter("qid", qid).executeUpdate();
    }

    @Transactional
    void deleteComponentById(UUID cid) {
        em.createNativeQuery("DELETE FROM component WHERE id = :cid").setParameter("cid", cid).executeUpdate();
    }

    @AfterEach
    void cleanup() {
        for (UUID qid : quotationIds) {
            try {
                deleteQuotationCascade(qid);
            } catch (Exception e) {
                System.err.println("[T-18 cleanup] quotation " + qid + " failed: " + e.getMessage());
            }
        }
        for (UUID cid : componentIds) {
            try {
                deleteComponentById(cid);
            } catch (Exception e) {
                System.err.println("[T-18 cleanup] component " + cid + " failed: " + e.getMessage());
            }
        }
        quotationIds.clear();
        componentIds.clear();
    }

    @Test
    @DisplayName("AC-22: 迁移后全库(line_item_id, component_id)重复组=0,唯一约束uq_qlcd_line_component存在")
    void migration_noDuplicateGroups_uniqueConstraintExists() {
        Number dupGroups = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM (" +
                                "  SELECT line_item_id, component_id FROM quotation_line_component_data" +
                                "  GROUP BY 1,2 HAVING count(*) > 1" +
                                ") t")
                .getSingleResult();
        assertEquals(0L, dupGroups.longValue(),
                "迁移应用后 quotation_line_component_data 不应再有 (line_item_id, component_id) 重复组,实际=" + dupGroups);

        Number constraintCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_qlcd_line_component'")
                .getSingleResult();
        assertEquals(1L, constraintCount.longValue(),
                "唯一约束 uq_qlcd_line_component 应存在于当前库(count应为1),实际=" + constraintCount
                        + " —— 若为0说明B-7迁移尚未应用到本测试库(test profile/cpq_db),需backend-engineer确认已提交迁移文件");

        // 已知 dev 库案例的逐行核对(守卫式,详见类注释的"已知限制")
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                        "SELECT tab_name FROM quotation_line_component_data WHERE line_item_id = :lid")
                .setParameter("lid", KNOWN_DEV_LINE_ITEM_ID)
                .getResultList();
        if (rows.isEmpty()) {
            System.out.println("[T-18] 已知dev库案例 lineItem=" + KNOWN_DEV_LINE_ITEM_ID
                    + " 在本测试库(test profile)中不存在,跳过逐行核对(见类注释已知限制) —— 需主线在dev库人工核对");
        } else {
            // 2026-08-29 主线实测修正:14行原始数据-6行重复=8行(不是最初文档推算的7);其中1条
            // (component_id=4a193e48-...)本就不在重复组内、tab_name 原生为空,迁移不应也没有动它
            // —— 判据因此是"恰好8行 且 恰好1行tab_name为空/其余7行非空",不是"全部非空"。
            assertEquals(8, rows.size(),
                    "已知案例lineItem" + KNOWN_DEV_LINE_ITEM_ID + "去重后应剩8条(14原始-6重复),实际=" + rows.size());
            long blankCount = rows.stream()
                    .filter(t -> t == null || t.toString().isEmpty())
                    .count();
            long nonBlankCount = rows.size() - blankCount;
            assertEquals(1L, blankCount,
                    "8条中应恰好1条tab_name原生为空(未参与重复组、迁移不应动它),实际空值行数=" + blankCount);
            assertEquals(7L, nonBlankCount,
                    "8条中应恰好7条tab_name非空(去重后保留的那些),实际非空行数=" + nonBlankCount);
        }
    }

    @Test
    @DisplayName("AC-23: 正常保存连续跑3次(结构不变) → 全部成功,不触发唯一约束冲突")
    void repeatedSaveDraft_threeTimes_noUniqueConstraintConflict() {
        UUID lineItemId = UUID.randomUUID();
        UUID qid = createDraftQuotationWithLine(lineItemId);
        Component comp = buildPlainComponent("投料");

        for (int i = 1; i <= 3; i++) {
            final int round = i;
            SaveDraftRequest req = new SaveDraftRequest();
            SaveDraftRequest.LineItemDraft d = new SaveDraftRequest.LineItemDraft();
            d.id = lineItemId;
            d.sortOrder = 0;
            SaveDraftRequest.ComponentDataDraft cd = new SaveDraftRequest.ComponentDataDraft();
            cd.componentId = comp.id;
            cd.tabName = "投料";
            cd.rowData = "[{\"值\":\"round-" + round + "\"}]";
            cd.sortOrder = 0;
            d.componentData = List.of(cd);
            req.lineItems = List.of(d);

            assertDoesNotThrow(() -> quotationService.saveDraft(qid, req),
                    "第" + round + "次连续保存不应抛出异常(唯一约束不应误伤正常UPSERT/回落全删全建路径)");
        }

        // 非空验证:确实只剩恰好1行(键选对了,不会自己把自己撞成两行/或漏掉)
        Number cnt = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", comp.id).getSingleResult();
        assertEquals(1L, cnt.longValue(), "连续3次保存后该(line_item_id, component_id)应恰好1行,实际=" + cnt);

        List<?> rd = em.createNativeQuery(
                        "SELECT row_data::text FROM quotation_line_component_data " +
                                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", comp.id).getResultList();
        assertFalse(rd.isEmpty());
        assertTrue(((String) rd.get(0)).contains("round-3"),
                "最终内容应是第3次保存的值,实际=" + rd.get(0));
    }
}
