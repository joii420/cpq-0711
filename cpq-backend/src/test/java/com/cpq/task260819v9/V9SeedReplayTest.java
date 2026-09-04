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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                            root.resolve("scripts"),
                            root.resolve("cpq-backend").resolve("scripts"),
                            taskDir(),
                            taskDir().resolve("golden")),
                    n -> (n.endsWith(".py") || n.endsWith(".sh") || n.endsWith(".js"))
                            && n.contains("seed")
                            && (n.contains("gen") || n.contains("build") || n.contains("v9")));
        }
        if (gen == null) {
            fail(notReady("AC-103", "找不到种子生成脚本（约定：文件名含 seed + gen/build/v9，"
                            + "位于 scripts/ · cpq-backend/scripts/ · 任务目录 · 任务目录/golden 之一）",
                    "cpq-backend #2 / B-42（D-82 要求脚本入仓、可重放）")
                    + "\n  💡 已知路径时可用环境变量指定：V9_SEED_GEN=<路径> ./mvnw test -Dtest=V9SeedReplayTest");
        }

        Path committed = resolveFromEnv(root, "V9_SEED_SQL");
        if (committed == null) {
            committed = discover(root, List.of(
                            root.resolve("cpq-backend/src/main/resources/db/migration"),
                            taskDir(),
                            taskDir().resolve("golden")),
                    n -> n.endsWith(".sql") && n.contains("semantic")
                            && (n.contains("seed") || n.contains("v9")));
        }
        if (committed == null) {
            fail(notReady("AC-103", "找不到仓库中已提交的种子 SQL（约定：*.sql 且文件名含 semantic + seed/v9）",
                    "cpq-backend #2 / B-42")
                    + "\n  💡 已知路径时可用环境变量指定：V9_SEED_SQL=<路径>");
        }

        System.out.println("[AC-103] 生成脚本   = " + gen);
        System.out.println("[AC-103] 已提交种子 = " + committed);

        byte[] committedBytes = Files.readAllBytes(committed);
        assertTrue(committedBytes.length > 0,
                "AC-103: 已提交的种子 SQL 是空文件 —— md5 相同会因为两边都空而假通过，这里先挡掉");
        String committedMd5 = md5(committedBytes);

        Path outDir = Files.createTempDirectory("v9-seed-replay-");
        String stdout;
        try {
            stdout = runGenerator(gen, outDir);

            // 产出可能是 stdout，也可能是写到 outDir 里的文件 —— 两种都接受，但必须<b>恰好一种</b>能对上
            List<Path> produced;
            try (Stream<Path> s = Files.walk(outDir)) {
                produced = s.filter(Files::isRegularFile).sorted().toList();
            }
            System.out.println("[AC-103] 脚本产出文件 = " + produced);
            System.out.println("[AC-103] 脚本 stdout 字节数 = " + stdout.getBytes(StandardCharsets.UTF_8).length);

            String replayMd5;
            String replaySource;
            if (produced.size() == 1) {
                replayMd5 = md5(Files.readAllBytes(produced.get(0)));
                replaySource = "产出文件 " + produced.get(0);
            } else if (produced.isEmpty()) {
                assertTrue(!stdout.isBlank(), notReady("AC-103",
                        "脚本既没产出文件、stdout 也是空的 —— 这正是 testing.md §4.4 说的「命令写错导致全程空输出，"
                                + "看起来和全部通过一模一样」，绝不能判通过", "cpq-backend #2 / B-42"));
                replayMd5 = md5(stdout);
                replaySource = "脚本 stdout";
            } else {
                // 多个产出：挑与已提交文件同名的那个
                final String committedName = committed.getFileName().toString();
                Path match = produced.stream()
                        .filter(p -> p.getFileName().toString().equals(committedName))
                        .findFirst().orElse(null);
                if (match == null) {
                    fail("AC-103: 脚本产出了 " + produced.size() + " 个文件，没有一个与已提交种子同名（"
                            + committed.getFileName() + "）。产出=" + produced
                            + "\n  ⚠️ 无法确定该拿哪个比 md5 —— 请 B-42 明确「可重放产物」是哪一个文件。");
                    return;
                }
                replayMd5 = md5(Files.readAllBytes(match));
                replaySource = "产出文件 " + match;
            }

            System.out.println("[AC-103] 已提交 md5 = " + committedMd5);
            System.out.println("[AC-103] 重跑   md5 = " + replayMd5 + "（来源：" + replaySource + "）");
            assertEquals(committedMd5, replayMd5,
                    "AC-103: 重跑生成脚本的产出必须与仓库已提交版本 md5 逐字节相同。"
                            + "\n  不相同意味着：① 有人手改过种子 SQL 没回改脚本，或 ② 脚本输出不确定（如带时间戳/随机 UUID）。"
                            + "\n  ②同样不合格 —— D-82 要的就是「Excel 改了能重跑」，不确定的输出让这条保证失效。"
                            + "\n  已提交=" + committed + "\n  重跑源=" + replaySource);
        } finally {
            deleteRecursively(outDir);
        }
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
