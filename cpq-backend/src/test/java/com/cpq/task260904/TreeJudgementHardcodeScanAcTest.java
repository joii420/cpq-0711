package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TC-22 —— <b>AC-22（反向 · 6 处硬编码树判据必须一并收编）</b>。
 *
 * <p>AC 原文（{@code 需求文档.md §3.3}）：
 * 「改造后全工程搜 {@code "BOM".equals}（用 {@code /usr/bin/grep -a}，本环境 {@code grep} 是
 *  {@code ugrep -I} 别名会静默返空）。<b>6 处全部改走双判据方法</b>。
 *  断言：① 业务代码中 {@code "BOM".equals(...)} 的出现次数为 <b>0</b>
 *  （<b>仅允许留在 §1.35 的双判据方法内部或注释里</b>）」。
 *
 * <h3>为什么这条要写成源码扫描而不是行为测试</h3>
 * AC 原文要求的就是一次全工程计数。AC-14 抓不到这些散点 ——
 * 它只抽样打开 27 个模板，而 {@code CardSnapshotService} 那处影响的是「跳没跳 live view」，
 * 行数可能一模一样（AC-22 原文自己点明了这一点）。
 *
 * <h3>🚫 关于「不读实现」</h3>
 * 本类<b>只做字面量计数与位置登记</b>，不解析、不引用、也不据实现语义写任何断言 ——
 * 断言完全来自 AC-22 原文。它是一条 lint，不是从实现反推出来的用例。
 *
 * <h3>基线口径（⚠️ 读结果时必看）</h3>
 * <b>真正的改动前基线是需求文档 §4.2⑦ 记的 6 处硬编码</b>
 * （{@code ComponentService} · {@code ComponentImportService} · {@code PublishedTemplateReader} ·
 *  {@code CardSnapshotService} · {@code QuoteBackfillCollector}，另加收口点 {@code BomTreeRenderService}）。
 *
 * <p>🚨 <b>本用例首次真跑时（2026-09-05 23:26）后端改造已在同一 worktree 里落地</b>，
 * 扫描结果是 <b>0 处非注释命中 / 962 个源文件</b> —— 剩下的 6 处文本全部落在
 * javadoc 或 {@code //} 注释里（形如「task-260904 B-18：…… 由硬编码 {@code "BOM".equals(...)} 收编为双判据」），
 * 按 AC-22① 原文属于豁免。
 * ⇒ <b>这个绿是「改完之后」的绿，不能反过来当成「本用例能抓到红」的证明。</b>
 * 想确认它真的会红，见 {@code test-report} 里给主线的证伪实验（把任意一处注释还原成真代码，重跑必须变红）。
 *
 * <h3>判据（🚨 2026-09-05 因证伪实验抓到假绿而收紧）</h3>
 * 旧判据是「非注释命中散落到 &gt;1 个文件才失败」——<b>只数个数，不看是哪个文件</b>。
 * 主线把一处注入 {@code PublishedTemplateReader.java:165} 后，它恰好成了「唯一的那个文件」，
 * 用例<b>检测到了、打印了、然后通过了</b>。现判据两条并列、缺一不可：
 * <ol>
 *   <li>非注释命中的文件集合 ⊆ <code>{TabSemanticResolver.java}</code>（<b>点名允许的文件</b>）；</li>
 *   <li>命中总数 ≤ {@code MAX_HITS_IN_COLLAPSE_POINT}（防止有人往收口点文件里塞新的散落判断）。</li>
 * </ol>
 * 失败信息点名<b>文件 : 行号 : 原文</b>。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-22 —— \"BOM\".equals 硬编码必须收敛到唯一的双判据实现处")
class TreeJudgementHardcodeScanAcTest extends Task260904Base {

    private static final String NEEDLE = "\"BOM\".equals";

    /**
     * §1.35 双判据方法的所在文件 —— <b>唯一允许出现 {@code "BOM".equals} 的地方</b>。
     * 路径相对 {@code cpq-backend/src/main/java/com/cpq}。
     */
    private static final String COLLAPSE_POINT = "component/service/TabSemanticResolver.java";

    /**
     * 收口点文件内部允许的<b>非注释</b>命中上限。
     * <p>2026-09-05 实测 = <b>0</b>：收口点判树用的是常量 {@code TAB_TYPE_TREE.equals(tabType)}
     * 而不是字面量。留这个阈值是为了防「把新的散落判断塞进收口点文件」这条绕过路径。
     * 🚫 抬高它必须有明确理由并同步改本注释 —— 否则本条就退化成了「数个数」，
     * 而「数个数」正是 2026-09-05 被证伪实验打穿的那版判据。
     */
    private static final int MAX_HITS_IN_COLLAPSE_POINT = 0;

    @Test
    @DisplayName("AC-22①：业务代码里的 \"BOM\".equals 只允许出现在唯一一个文件（双判据方法所在处）")
    void ac22_hardcodedTreeJudgementCollapsedToOnePlace() throws IOException {
        File srcRoot = locateBackendMainJava();
        assertNotNull(srcRoot, "定位不到 cpq-backend/src/main/java —— 本用例无法执行（这是 harness 故障，不是 AC 结论）");

        Map<String, List<String>> hitsByFile = new LinkedHashMap<>();
        int total = 0;
        try (Stream<Path> paths = Files.walk(srcRoot.toPath())) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            // 🚨 阳性对照（testing.md §4.4）：扫描器本身必须证明「能扫到东西」。
            //    一个扫不到任何文件的扫描器，结果和「全部通过」长得一模一样。
            assertTrue(javaFiles.size() > 100,
                    "扫描器只找到 " + javaFiles.size() + " 个 .java 文件 ⇒ 路径不对/扫描没生效，"
                            + "此时『0 处命中』是空验证而不是结论。");
            System.out.println("[AC-22] 扫描 " + javaFiles.size() + " 个后端源文件，根目录=" + srcRoot);

            for (Path p : javaFiles) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String raw = lines.get(i);
                    if (!raw.contains(NEEDLE)) continue;
                    String t = raw.trim();
                    // 注释里的出现按 AC 原文豁免
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                    String rel = srcRoot.toPath().relativize(p).toString();
                    hitsByFile.computeIfAbsent(rel, k -> new ArrayList<>()).add((i + 1) + ": " + t);
                    total++;
                }
            }
        }

        StringBuilder report = new StringBuilder();
        hitsByFile.forEach((file, lines) -> {
            report.append("\n  ").append(file).append(" (").append(lines.size()).append(" 处)");
            lines.forEach(l -> report.append("\n      ").append(l));
        });
        System.out.println("[AC-22] 非注释命中 " + total + " 处 / " + hitsByFile.size() + " 个文件：" + report);

        // ═══ 判据：两条并列，缺一不可（🚫 不许退化成「数个数」）═══
        //
        // 🚨 2026-09-05 主线证伪实验抓到的假绿：本用例原判据是 `hitsByFile.size() > 1 → fail`，
        //    只检查「没散落到多个文件」，**没检查那唯一的文件是不是收口点**。
        //    主线把一处 "BOM".equals 注入 PublishedTemplateReader.java:165 后，它恰好成了
        //    「唯一的那个文件」⇒ 用例检测到了、打印了、然后**通过了**。
        //    ⇒ AC-22① 原文是「仅允许留在 §1.35 的双判据方法内部」，判据必须**点名文件**。
        List<String> outsiders = hitsByFile.keySet().stream()
                .filter(f -> !COLLAPSE_POINT.equals(f.replace(File.separatorChar, '/')))
                .toList();
        if (!outsiders.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (String f : outsiders) {
                detail.append("\n  ").append(f);
                hitsByFile.get(f).forEach(l -> detail.append("\n      ").append(l));
            }
            fail("AC-22①：\"BOM\".equals 出现在**收口点以外**的 " + outsiders.size() + " 个文件里。"
                    + "\nAC 原文只允许它留在双判据方法内部，即 " + COLLAPSE_POINT + "（或注释里）。"
                    + "\n越界位置（文件 : 行号 : 原文）：" + detail
                    + "\n改动前基线是 6 处 / 6 文件（需求文档 §4.2⑦）。"
                    + "\n⚠️ 归因时先 A/B 对照干净 master 看是不是改动前就有的，但无论新旧，AC-22 要求的都是收编。");
        }
        // 第二条：防止有人把新的散落判断塞进收口点文件本身
        assertTrue(total <= MAX_HITS_IN_COLLAPSE_POINT,
                "AC-22①：收口点 " + COLLAPSE_POINT + " 内部的 \"BOM\".equals 非注释命中 " + total + " 处，"
                        + "超过既有的 " + MAX_HITS_IN_COLLAPSE_POINT + " 处 ⇒ 有人在收口点里新塞了散落判断。"
                        + "\n（该阈值取自 2026-09-05 实测：收口点用的是常量 TAB_TYPE_TREE.equals(...)，"
                        + "字面量非注释命中为 0。要抬高它必须有明确理由并同步改这里的注释。）"
                        + "\n命中明细：" + report);

        System.out.println("[AC-22①] ✅ 收口点以外 0 处；收口点内部 " + total + " 处（上限 "
                + MAX_HITS_IN_COLLAPSE_POINT + "）");
    }

    /** 从 {@code user.dir} 向上找 {@code cpq-backend/src/main/java}（兼容从 cpq-backend/ 或仓库根跑）。 */
    private static File locateBackendMainJava() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File c1 = new File(dir, "src/main/java/com/cpq");
            if (c1.isDirectory()) return c1;
            File c2 = new File(dir, "cpq-backend/src/main/java/com/cpq");
            if (c2.isDirectory()) return c2;
        }
        return null;
    }
}
