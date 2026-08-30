package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineComponentData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-05（AC-5 阳性 + AC-6 反向）。
 *
 * <p><b>为什么改成纯单测，不走端到端</b>（2026-08-29 主线审用例设计后裁决）：判据条件②原本按
 * "该行任意 comp_data 的 snapshot_rows 非空"实现，实测（本线自己构造 orphan comp_data 时）
 * 暴露一个真缺陷——orphan（不挂在模板 tabs 里的）comp_data 也会被条件②计入，导致
 * "某行有 orphan comp_data + 模板内组件合法返 0 行"被误判成"算早了"，写不进库且 IS NULL
 * 判据会反复重选、反复误判，形成新的锁死（主线在 dev 库实测到 1 行真实 orphan 数据，证明
 * 不是纸上谈兵）。主线已让后端把条件②收窄为"componentId 必须出现在 {@code builtQuoteJson}
 * 的 tabs 里，且该 componentId 对应的 {@code snapshot_rows} 非空"。
 *
 * <p>收窄后，命中条件的语义变成"这个组件的 snapshot_rows 有数据，但它渲染出的 baseRows 是 0"——
 * 这只可能来自 Pass1（build，读快照渲染 baseRows）与 Pass1.5（comp_data 预载）两次读取之间
 * 恰好跨越①步一次真实提交的时序差。单线程端到端测试里,同一事务内的两次读取必然读到同一份
 * 数据、不可能分叉——真实触发这个组合需要外部并发写入（即 AC-4 的还原实验范畴，由主线负责）。
 * 因此本条改为直接单测 {@link CardSnapshotService#isEarlySkeletonRender}（包级可见），
 * 绕开"如何在单线程里制造这个数据分叉"这个不可解的构造问题，直接摆数据验证判据本身对不对。
 *
 * <p><b>唯一读取实现的授权范围</b>：主线已在派工消息里给出 {@code isEarlySkeletonRender} 的
 * 精确签名、参数语义与收窄后的匹配规则（"componentId 需出现在 tabs 里"），本文件据此构造
 * {@code builtQuoteJson}/{@code cds} 两侧输入，未额外阅读该方法之外的 {@code CardSnapshotService}
 * 业务逻辑。
 */
@QuarkusTest
class CardSnapshotEarlySkeletonGuardTest {

    @Inject CardSnapshotService svc;

    private static QuotationLineComponentData cd(UUID componentId, String snapshotRows) {
        QuotationLineComponentData d = new QuotationLineComponentData();
        d.id = UUID.randomUUID();
        d.lineItemId = UUID.randomUUID();
        d.componentId = componentId;
        d.tabName = "任意页签";
        d.snapshotRows = snapshotRows;
        return d;
    }

    private static String oneTabJson(UUID componentId, boolean emptyBaseRows) {
        String baseRows = emptyBaseRows ? "[]" : "[{\"driverRow\":{\"名称\":\"x\"},\"basicDataValues\":{}}]";
        return "{\"tabs\":[{\"componentId\":\"" + componentId + "\",\"tabName\":\"T\",\"baseRows\":" + baseRows + "}]}";
    }

    @Test
    @DisplayName("T-05-1(AC-5 阳性): 命中组件在 tabs 内、baseRows=0、snapshot_rows 非空 → true")
    void true_whenTabComponentHasEmptyBaseRowsButNonEmptySnapshotRows() {
        UUID compA = UUID.randomUUID();
        String builtQuoteJson = oneTabJson(compA, true);
        List<QuotationLineComponentData> cds = List.of(
                cd(compA, "[{\"driverRow\":{\"名称\":\"真实数据\"},\"basicDataValues\":{}}]"));

        boolean result = svc.isEarlySkeletonRender(builtQuoteJson, cds);
        assertTrue(result, "该 tab 唯一组件的 snapshot_rows 非空但 baseRows=0,应判定为算早了");
    }

    @Test
    @DisplayName("T-05-2(AC-6 反向 · orphan 不误判): comp_data 的 componentId 不在 tabs 内 → false")
    void false_whenComponentDataIsOrphanNotInTabs() {
        UUID compA = UUID.randomUUID(); // 模板内唯一组件,baseRows=0(legit,例如 $view WHERE FALSE)
        UUID compB = UUID.randomUUID(); // orphan:不挂在任何 tab 里
        String builtQuoteJson = oneTabJson(compA, true);
        List<QuotationLineComponentData> cds = List.of(
                cd(compB, "[{\"driverRow\":{\"名称\":\"orphan数据\"},\"basicDataValues\":{}}]"));

        boolean result = svc.isEarlySkeletonRender(builtQuoteJson, cds);
        assertFalse(result, "非空 snapshot_rows 属于一个不在 tabs 内的 orphan 组件时不应误判——" +
                "这正是本线在 dev 库实测到的真实缺陷场景(orphan comp_data + 模板内组件合法返0行)");
    }

    @Test
    @DisplayName("T-05-3(AC-6): 同一 componentId 在 tabs 内,但 snapshot_rows 本身合法为空 → false")
    void false_whenSnapshotRowsLegitimatelyEmpty() {
        UUID compA = UUID.randomUUID();
        String builtQuoteJson = oneTabJson(compA, true);

        assertFalse(svc.isEarlySkeletonRender(builtQuoteJson, List.of(cd(compA, "[]"))),
                "snapshot_rows='[]' 是合法空结果,不应判定为算早了");
        assertFalse(svc.isEarlySkeletonRender(builtQuoteJson, List.of(cd(compA, null))),
                "snapshot_rows=null 是合法空结果,不应判定为算早了");
        assertFalse(svc.isEarlySkeletonRender(builtQuoteJson, List.of()),
                "该行没有任何 comp_data 记录时,不应判定为算早了");
    }

    @Test
    @DisplayName("T-05-4(AC-10-③ SUBTOTAL 陷阱回归): 只要有任一 tab 非空,合计就不是0 → false")
    void false_whenAnyOtherTabHasNonEmptyBaseRows() {
        UUID compA = UUID.randomUUID(); // baseRows=0,snapshot_rows 非空(单独看会触发条件)
        UUID compB = UUID.randomUUID(); // baseRows 非空(如 SUBTOTAL 旁边的正常 driver tab)
        String builtQuoteJson = "{\"tabs\":[" +
                "{\"componentId\":\"" + compA + "\",\"tabName\":\"T1\",\"baseRows\":[]}," +
                "{\"componentId\":\"" + compB + "\",\"tabName\":\"T2\"," +
                "\"baseRows\":[{\"driverRow\":{\"名称\":\"x\"},\"basicDataValues\":{}}]}" +
                "]}";
        List<QuotationLineComponentData> cds = List.of(
                cd(compA, "[{\"driverRow\":{\"名称\":\"真实数据\"},\"basicDataValues\":{}}]"));

        assertFalse(svc.isEarlySkeletonRender(builtQuoteJson, cds),
                "判据必须是'所有页签 baseRows 合计为0',只要有一个 tab 非空就不应判定为算早了" +
                "(写成'任一页签为0'会在 SUBTOTAL 场景里误判,AC-10-③)");
    }

    @Test
    @DisplayName("T-05-5(边界): builtQuoteJson 为 null/空白/无 tabs → 一律 false,不抛异常")
    void false_whenBuiltQuoteJsonNullOrBlankOrNoTabs() {
        UUID compA = UUID.randomUUID();
        List<QuotationLineComponentData> cds = List.of(cd(compA, "[{\"driverRow\":{}}]"));

        assertFalse(svc.isEarlySkeletonRender(null, cds), "builtQuoteJson=null 应返回 false");
        assertFalse(svc.isEarlySkeletonRender("", cds), "builtQuoteJson='' 应返回 false");
        assertFalse(svc.isEarlySkeletonRender("   ", cds), "builtQuoteJson=空白 应返回 false");
        assertFalse(svc.isEarlySkeletonRender("{\"tabs\":[]}", cds),
                "0 个 tab(如模板0 driver组件)不归本判据管,应返回 false");
    }
}
