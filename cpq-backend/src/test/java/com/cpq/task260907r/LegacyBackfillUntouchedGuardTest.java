package com.cpq.task260907r;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-15① —— B-14 / B-15 反向守卫取证</b>（AC-15 前半）：
 * 老回填三文件 {@code QuoteBackfillService} / {@code QuoteBackfillCollector} /
 * {@code QuoteBackfillColumnMapper} 的 {@code git diff} 为空。
 *
 * <h3>为什么<u>不是</u> {@code @QuarkusTest}</h3>
 * 本条是纯静态取证，压根不需要应用启动。而 2026-09-07 实测：本 worktree 的 Quarkus <b>起不来</b>
 * （Registry 声明了 {@code source_quotation_id} 与 13 张 {@code _record}，迁移却在
 * {@code migration-pending-260907/} 未应用 ⇒ 启动自检报 39 处不一致）。
 * 若把本条挂在 {@code @QuarkusTest} 上，它会被 <b>skip</b> ——
 * 于是「B-14/B-15 取证」这件明明做得到的事，会以「跳过」的形态消失在报告里。
 *
 * <p>⇒ 纪律：<b>不需要容器的断言就别挂容器</b>。挂上去等于把自己的可执行性
 * 绑给了一个随时会挂的前置。
 *
 * <p>依据：第一段 {@code D-13}「摘 UI + 停用端点，代码保留」，其 §4.5 明写这三个「保留不动」；
 * 本段另起新路径，🚫 不改老链路。
 */
@DisplayName("T-15① · 老回填三文件零改动（B-14/B-15 反向守卫，非 @QuarkusTest）")
class LegacyBackfillUntouchedGuardTest {

    private static final List<String> GUARDED = List.of(
            "QuoteBackfillService.java", "QuoteBackfillCollector.java", "QuoteBackfillColumnMapper.java");

    @Test
    @DisplayName("三个老回填文件相对 master 的 git diff 为空")
    void legacyBackfillFilesUntouched() {
        // 🚨 阳性对照：先证明这三个文件在仓库里确实存在。
        //    文件被整个删掉时 `git diff <path>` 同样为空 —— 那时「diff 为空」是最危险的假绿。
        List<String> paths = new ArrayList<>();
        for (String name : GUARDED) {
            String p = locate(name);
            assertTrue(p != null,
                    "AC-15：受守卫文件 " + name + " 在仓库里找不到 —— 🚨 文件被删时 git diff 同样为空，"
                            + "此时「diff 为空」是假绿，必须硬失败。");
            paths.add(p);
        }
        assertEquals(3, paths.size(), "应定位到 3 个受守卫文件，实际 " + paths);

        // 🚨 第二重阳性对照：确认 diff 命令本身在这个工作区确实「能输出东西」。
        //    命令写错导致全程空输出，看起来和「全部通过」一模一样（testing.md §4.4）。
        String allDiff = git("diff", "master", "--stat").trim();
        String untracked = git("status", "--porcelain").trim();
        assertTrue(!allDiff.isEmpty() || !untracked.isEmpty(),
                "🚨 阳性对照失败：本 worktree 相对 master 既无 diff 也无未跟踪改动 ⇒ "
                        + "要么 git 命令没生效，要么工作区是空的。此时「三文件 diff 为空」不构成证据。");

        List<String> args = new ArrayList<>(List.of("diff", "master", "--", ""));
        args.remove(args.size() - 1);
        args.addAll(paths);
        String guardedDiff = git(args.toArray(new String[0])).trim();

        assertTrue(guardedDiff.isEmpty(),
                "AC-15：老回填三文件相对 master 有改动，AC 要求 git diff 为空 ——\n" + guardedDiff);

        System.out.println("[T-15①] B-14/B-15 反向守卫取证 ✅");
        System.out.println("  受守卫文件（3 个，均存在）：" + paths);
        System.out.println("  三文件 diff master：<空>");
        System.out.println("  阳性对照 · 本分支全量 diff --stat：\n" + indent(allDiff));
        System.out.println("  阳性对照 · git status --porcelain：\n" + indent(untracked));
    }

    private static String indent(String s) {
        if (s.isEmpty()) return "    （无）";
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\n")) sb.append("    ").append(l).append('\n');
        return sb.toString();
    }

    /** 仓库根（{@code user.dir} 是 {@code cpq-backend/}，仓库根是它的父目录）。 */
    private File repoRoot() {
        return new File(System.getProperty("user.dir")).getParentFile();
    }

    private String locate(String fileName) {
        for (String line : git("ls-files", "*/" + fileName).split("\n")) {
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
}
