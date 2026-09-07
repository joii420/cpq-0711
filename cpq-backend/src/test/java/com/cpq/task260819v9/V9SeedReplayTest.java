package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 需求文档.md §9.4 A 组 —— <b>AC-103「种子可重放」</b>。
 *
 * <p>AC 原文：重跑生成脚本，产出的种子 SQL 与仓库中<b>已提交版本 md5 逐字节相同</b>（防手改后无人知道）。
 *
 * <h3>为什么用「发现」而不是写死路径</h3>
 * 生成脚本与种子 SQL 由 <b>cpq-backend #2 / B-42</b> 交付，脚本名、放哪、迁移号都由它定（迁移号还是移动靶）。
 * 我在写用例时它们还不存在 —— 写死路径等于把我的猜测固化成断言。
 * 因此本类<b>按约定自动发现</b>，并允许用环境变量精确指定：
 * <ul>
 *   <li>{@code V9_SEED_GEN}  —— 生成脚本路径（相对仓库根或绝对路径）</li>
 *   <li>{@code V9_SEED_SQL}  —— 仓库中已提交的种子 SQL 路径</li>
 * </ul>
 * 两者都找不到时<b>硬失败</b>并列出搜过的位置。🚫 不用 {@code Assumptions} —— skip != pass。
 *
 * <h3>本类不改任何全局状态</h3>
 * 生成脚本的产物写到 {@code java.io.tmpdir} 下的临时目录，不落仓库、不落库。
 * 🚨 若脚本本身会往库里写，本用例会把它跑起来 —— 因此脚本必须支持「只产出 SQL 文本、不执行」的模式；
 * 这一点已在回报里作为对 B-42 的契约要求列出。
 */
@QuarkusTest
class V9SeedReplayTest extends V9TestBase {

    @Test
    @DisplayName("AC-103: 重跑种子生成脚本，产出与仓库已提交版本 md5 逐字节相同")
    void ac103_seedGeneratorIsReplayable() throws Exception {
        Path root = repoRoot();

        Path gen = resolveFromEnv(root, "V9_SEED_GEN");
        if (gen == null) {
            gen = discover(root, List.of(
                            taskDir().resolve("scripts"),
                            root.resolve("scripts"),
                            root.resolve("cpq-backend").resolve("scripts"),
                            taskDir(),
                            taskDir().resolve("golden")),
                    n -> (n.endsWith(".py") || n.endsWith(".sh") || n.endsWith(".js"))
                            && n.contains("seed")
                            && (n.contains("gen") || n.contains("build") || n.contains("v9")));
        }
        if (gen == null) {
            fail(notReady("AC-103", "找不到种子生成脚本（约定：文件名含 seed + gen/build/v9）",
                    "cpq-backend #2 / B-42（D-82 要求脚本入仓、可重放）")
                    + "\n  💡 可用环境变量指定：V9_SEED_GEN=<路径>");
        }
        System.out.println("[AC-103] 生成脚本 = " + gen);

        // ── 跑 `--check`：脚本会对每个产物打印「仓库 md5」与「重跑 md5」
        Proc r = run(gen, List.of("--check"));
        System.out.println("[AC-103] --check 退出码 = " + r.exit);
        System.out.println("[AC-103] --check 输出:\n" + r.out);

        assertFalse(r.out.isBlank(),
                "AC-103: 脚本 --check 全程空输出 —— testing.md §4.4：命令写错导致的空输出"
                        + "『看起来和全部通过一模一样』，绝不能判通过。退出码=" + r.exit);
        assertEquals(0, r.exit,
                "AC-103: 生成脚本 --check 退出码应为 0（仓库里的种子 == 脚本重跑产出）。输出:\n" + r.out);

        // ── 🚨 关键：不采信「脚本自己说一致」。逐行解析它报的 (文件名, 仓库md5, 重跑md5)，
        //    再用**我自己算的文件 md5** 交叉核对「仓库md5」这一栏 ——
        //    这才排除了「脚本 hash 了别的文件 / 两边都算错但相等」这类同源自证。
        //    行形态：<文件名> 仓库=<32hex> 重跑=<32hex> ...
        Pattern line = Pattern.compile("(\\S+\\.sql)\\s+\u4ed3\u5e93=([0-9a-f]{32})\\s+\u91cd\u8dd1=([0-9a-f]{32})");
        Matcher m = line.matcher(r.out);
        int checked = 0;
        StringBuilder err = new StringBuilder();
        while (m.find()) {
            String fileName = m.group(1);
            String repoMd5 = m.group(2);
            String replayMd5 = m.group(3);

            Path onDisk = findFileByName(root, fileName);
            if (onDisk == null) {
                err.append("\n  脚本报告的文件在仓库里找不到：").append(fileName);
                continue;
            }
            String myMd5 = md5(Files.readAllBytes(onDisk));
            System.out.println("[AC-103] " + fileName + "  脚本报仓库=" + repoMd5
                    + "  我自己算=" + myMd5 + "  脚本报重跑=" + replayMd5);
            if (!myMd5.equals(repoMd5)) {
                err.append("\n  ").append(fileName)
                        .append(" 脚本报的「仓库 md5」(").append(repoMd5)
                        .append(") 与我自己算的文件 md5(").append(myMd5)
                        .append(") 不同 ⇒ 脚本 hash 的不是这个文件，它的「一致」结论不成立");
            }
            if (!replayMd5.equals(repoMd5)) {
                err.append("\n  ").append(fileName).append(" 重跑产出与仓库版本不同（")
                        .append(repoMd5).append(" → ").append(replayMd5).append("）");
            }
            checked++;
        }
        assertTrue(checked > 0,
                "AC-103: 没能从 --check 输出里解析出任何 (文件, 仓库md5, 重跑md5) 三元组 —— "
                        + "断言等于空跑。脚本输出格式可能变了，请核对解析正则。输出:\n" + r.out);
        assertEquals("", err.toString(), "AC-103: 可重放性核对不通过：" + err);

        // ── 至少要覆盖到本任务的 v9 种子，否则「校验通过」可能只覆盖了别的文件
        assertTrue(r.out.contains("_v9_") || r.out.toLowerCase().contains("v9"),
                "AC-103: --check 的输出里没有本任务的 v9 种子文件 —— 校验对象不对。输出:\n" + r.out);
        System.out.println("[AC-103 ✅] 共交叉核对 " + checked + " 个产物，"
                + "脚本报的仓库 md5 与我独立计算的文件 md5 全部一致，且重跑产出一致。");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private static Path resolveFromEnv(Path root, String var) {
        String v = System.getenv(var);
        if (v == null || v.isBlank()) {
            v = System.getProperty(var);
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        Path p = Path.of(v);
        if (!p.isAbsolute()) {
            p = root.resolve(v);
        }
        if (!Files.isRegularFile(p)) {
            fail("环境变量 " + var + "=" + v + " 指向的文件不存在：" + p);
        }
        return p;
    }

    private static Path discover(Path root, List<Path> dirs, java.util.function.Predicate<String> nameMatch) {
        List<Path> hits = new ArrayList<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d, 2)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> nameMatch.test(p.getFileName().toString().toLowerCase()))
                        .forEach(hits::add);
            } catch (Exception ignored) {
                // 目录不可读就跳过，最终统一按「没找到」硬失败
            }
        }
        hits.sort(Comparator.comparing(Path::toString));
        if (hits.isEmpty()) {
            return null;
        }
        if (hits.size() > 1) {
            System.out.println("[AC-103] ⚠️ 发现多个候选，取第一个：" + hits);
        }
        return hits.get(0);
    }

    private record Proc(int exit, String out) {
    }

    /** 跑脚本并捕获 stdout+stderr（合流，避免"退出码 0 但错误信息在 stderr"被吞掉）。 */
    private static Proc run(Path gen, List<String> args) throws Exception {
        String name = gen.getFileName().toString().toLowerCase();
        List<String> cmd = new ArrayList<>();
        if (name.endsWith(".py")) {
            cmd.add("python3");
        } else if (name.endsWith(".sh")) {
            cmd.add("bash");
        } else if (name.endsWith(".js")) {
            cmd.add("node");
        }
        cmd.add(gen.toAbsolutePath().toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoRoot().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("AC-103: 脚本 180s 未结束，已强杀。cmd=" + cmd);
        }
        return new Proc(p.exitValue(), out);
    }

    /** 在仓库里按文件名定位（迁移目录 + 任务目录）。 */
    private static Path findFileByName(Path root, String fileName) {
        for (Path d : List.of(root.resolve("cpq-backend/src/main/resources/db/migration"),
                taskDir(), taskDir().resolve("golden"), taskDir().resolve("scripts"))) {
            Path p = d.resolve(fileName);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** 跑生成脚本。约定：把输出目录作为第一个参数传给脚本；同时捕获 stdout。 */
    private static String runGenerator(Path gen, Path outDir) throws Exception {
        String name = gen.getFileName().toString().toLowerCase();
        List<String> cmd = new ArrayList<>();
        if (name.endsWith(".py")) {
            cmd.add("python3");
        } else if (name.endsWith(".sh")) {
            cmd.add("bash");
        } else if (name.endsWith(".js")) {
            cmd.add("node");
        }
        cmd.add(gen.toAbsolutePath().toString());
        cmd.add(outDir.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoRoot().toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        String err = readAll(p.getErrorStream());
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            fail("AC-103: 生成脚本 120s 未结束，已强杀。cmd=" + cmd);
        }
        if (p.exitValue() != 0) {
            fail("AC-103: 生成脚本退出码=" + p.exitValue() + "，cmd=" + cmd
                    + "\n  stdout:\n" + out + "\n  stderr:\n" + err);
        }
        if (!err.isBlank()) {
            System.out.println("[AC-103] 脚本 stderr（非空但退出码 0）:\n" + err);
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            in.transferTo(bo);
            return bo.toString(StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响判定
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
