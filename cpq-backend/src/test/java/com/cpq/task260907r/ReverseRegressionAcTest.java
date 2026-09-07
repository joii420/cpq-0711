package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-15 / T-16 / T-17 —— 反向回归</b>
 * （AC-15 老回填链路行为不变 · AC-16 核价两套数据集不被波及 · AC-17 选配链路不被波及）
 *
 * <h3>🚨 三条 AC 已于 2026-09-07 按「活数据陷阱」重写，本类照新版写</h3>
 * 原判据都是「与改动前<b>逐行 md5 相同</b>」，看着像不变量，<b>实际不是</b> ——
 * md5 罩住的是共享库里别的会话也在写的行。
 * 🔬 实证：在 <b>master 同码</b>上重采基线，14 个端点里 <b>13 个逐字节相同、1 个仍漂移</b>
 * （{@code total} 47→49，别的会话写的）。同一份代码、纯数据漂移，diff 照红。
 *
 * <p>⇒ 本类一律遵守两条共同纪律：
 * <ol>
 *   <li>比对范围按 {@code T260907R-} 前缀 / 本次夹具的轴值<b>收窄</b>；确需整体比时拆
 *       <b>结构层严格逐字</b>（{@link #assertStructureIdentical}）+ <b>数据层差异逐条可归因</b>两层。</li>
 *   <li><b>任何 diff 类断言之前先断言 HTTP 状态码</b>（{@link #requireStatusBeforeDiff}）——
 *       实证：一轮 session 过期，14 个端点全返 401，diff 忠实报出「14/14 全漂移」，
 *       差点产出「本任务把所有维护端点都搞坏了」的<b>假红</b>报告。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("AC-15/16/17 · 反向回归")
class ReverseRegressionAcTest extends Task260907RBase {

    /**
     * <b>T-15②（AC-15 后半，2026-09-07 重写）</b>：对<b>同一张</b>存量老单走核价通过，
     * 其回填摘要（{@code versionedGroups}/{@code addedRows}/{@code deletedRows}/{@code changedRows}
     * 四个数字）改动前后相同。
     *
     * <p>⚠️ AC 原文明写：必须是<b>同一张单、同一时刻窗口内</b>的 A/B，
     * 🚫 不许拿历史记录的数字比 —— 历史数字是别的时刻别的库状态下采的，必然漂移。
     */
    @Test
    @DisplayName("T-15② · 同一张老单的回填摘要四个数字，改动前后相同（同时刻窗口 A/B）")
    void t15_legacyBackfillSummaryUnchangedOnSameOrder() {
        throw pending("AC-15②：A/B 需在同一时刻窗口内、对同一张存量老单各跑一次核价通过预览，"
                + "比对 summary 的 versionedGroups/addedRows/deletedRows/changedRows 四个数字。"
                + "⛔ 阻塞点有二：① 新回填尚未挂上，A 侧（改动后）跑不出来；"
                + "② 需要一张**存量老单**（不走 ds_ 新链路）作夹具，且须在 S-7 全库清空执行前采样。"
                + "🚫 不拿历史记录的数字充当 B 侧 —— AC 原文明令禁止。");
    }

    /**
     * <b>T-16（AC-16，2026-09-07 重写为两层）</b>：核价两套数据集
     * （{@code COST_BASIC} / {@code COST_DETAIL}）的<b>导入与维护端保存</b>不被波及。
     *
     * <p>① <b>结构层严格</b>：两条路径的响应字段集、嵌套结构、HTTP 状态码与错误码，改动前后逐字相同；
     * ② <b>数据层可归因</b>：落库结果的差异必须逐条能归因到本次改动之外的原因（别的会话写库 / 时间戳），
     *    比对范围收窄到本次夹具自己写入的行；
     * ③ <b>前置</b>：做任何 diff 之前先断言 HTTP 状态码。
     *
     * <p>⚠️ 与 {@code 报价侧加客户维度} 的 {@code AC-6} 是同一个回归面
     * （{@code VersionedGroupWriter} 三套共用），已确认两条 AC 不互相污染：
     * 其 {@code AC-6} 限定在「导入 + 维护端保存」两条路，<b>本段新增的核价通过写点不在其比对范围内</b>。
     */
    @Test
    @DisplayName("T-16 · COST_BASIC/COST_DETAIL 结构层逐字相同 + 数据层差异可归因")
    void t16_costingDatasetsUnaffected() {
        throw pending("AC-16：需在同一份 T260907R- 夹具上，对 COST_BASIC / COST_DETAIL 的"
                + "「导入」与「维护端保存」两条路各跑一次 A/B。"
                + "⛔ 阻塞点：B 侧（改动前）= 当前 master 同码，A 侧（改动后）需后端实现落地。"
                + "本类已备好两层判据的工具：结构层走 assertStructureIdentical(jsonShape 拍扁路径+类型，"
                + "值漂移与数组长度不影响它)，数据层走 scopedDigest(按夹具轴值收窄，🚫 不整表 md5)，"
                + "diff 前一律先过 requireStatusBeforeDiff。");
    }

    /**
     * <b>T-17（AC-17，2026-09-07 重写）</b>：选配链路不被波及。
     *
     * <p>① {@code ConfigureProductService} / {@code SelDsQuoteWriter} 写入 {@code ds_quote_*} 的结果，
     *    <b>在本次夹具自己写入的行范围内</b>逐行相同（🚫 不许整表 md5）；
     * ② 报价单详情页渲染出的<b>页签数与每页签行数</b>改动前后相同（🚫 不写「渲染正常」，写具体计数）；
     * ③ 核价通过后该单状态 = {@code APPROVED}，且其涉及的料号组<b>版本号增量</b>与改动前相同。
     */
    @Test
    @DisplayName("T-17 · 选配链路：夹具行范围内逐行相同 + 页签/行数计数相同 + 版本号增量相同")
    void t17_selectionConfigChainUnaffected() {
        throw pending("AC-17：需改动前后各跑一次「选配建产品 → 打开报价单 → 提交 → 核价通过」。"
                + "⛔ 阻塞点：A 侧需后端实现落地。"
                + "⚠️ 断言②必须落成**具体计数**（页签数 + 每页签行数），"
                + "🚫 不许写「渲染正常」—— 那不是可观测断言。");
    }

    // ═══════════════════════ 工具 ═══════════════════════

    /** 仓库根（{@code user.dir} 是 {@code cpq-backend/}，仓库根是它的父目录）。 */
    private File repoRoot() {
        return new File(System.getProperty("user.dir")).getParentFile();
    }

    private String locate(String fileName) {
        String out = git("ls-files", "*/" + fileName);
        for (String line : out.split("\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return null;
    }

    private String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(repoRoot()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out;
        } catch (Exception e) {
            throw new AssertionError("git 调用失败（用例环境问题，不是业务结论）：" + List.of(args) + " → " + e, e);
        }
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException("⛔ 待接实现 / 待 A 侧落地（**不是被测功能的结论**）：" + what);
    }
}
