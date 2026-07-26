package com.cpq.datasource.sqlview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0725 T4 · AC-17 门禁 7.4 —— {@code QuotePendingScope.open(} 调用点白名单单测（结构性保证）。
 *
 * <p><b>为什么需要这个测试</b>：D' 方案的全部安全性建立在"核价侧任何入口一律不调用 {@code open()}"
 * 这一条不变式上（见 {@link QuotePendingScope} 类注释）。这条不变式今天靠人工 grep 维持——
 * 人工 grep 会腐化（下次有人在核价链路新增一次 {@code open()} 调用，不会有任何编译错误或运行时
 * 报错，只会静默改写出带 pending 数据/{@code __v6_id} 的核价查询）。本测试把这条不变式机器化：
 * 遍历 {@code src/main/java} 全部源码，断言含 {@code QuotePendingScope.open(} 调用的文件集合
 * <b>精确等于</b>白名单集合（backtask T3 的 P1/P2/P3/P4 四个报价侧权威 set 点所在的 3 个文件）。
 *
 * <p><b>{@code CardSnapshotService.java} 的特殊性</b>：该文件<b>同时</b>包含报价侧（合法，P2/P4）
 * 与核价侧（禁止）的方法，文件级白名单不足以保护——核价侧方法里新增一次 {@code open()} 调用，
 * 文件级检查仍会通过（因为该文件本来就在白名单里）。因此对这一个文件额外做<b>方法体级</b>断言：
 * 用一个不依赖字符串/注释误判括号的极简 Java 词法屏蔽 + 括号深度扫描，从原文里精确切出每个目标
 * 方法（含全部重载）的方法体文本，分别断言"报价侧方法体内必须含 open()"与"核价侧方法体内绝不
 * 能含 open()"。该切分逻辑不依赖任何硬编码行号——方法改行号/文件改版不会让测试失效或误报。
 */
class QuotePendingScopeOpenWhitelistTest {

    private static final String OPEN_CALL = "QuotePendingScope.open(";

    /** 白名单：允许调用 {@code QuotePendingScope.open(} 的文件（相对 src/main/java，'/' 分隔）。 */
    private static final Set<String> WHITELIST_FILES = Set.of(
            // P2（refreshQuoteCardValues/dryRunTokenRows）+ P4（ensureExcelValues 报价分支）
            "com/cpq/quotation/service/CardSnapshotService.java",
            // P3：batch-expand phase1 / phase2 合桶 / runSingleTask，均按 task.usage 门控
            "com/cpq/component/resource/ComponentResource.java",
            // P1：snapshotLines 主战场（建单/加产品/saveDraft/从基础刷新/报价树共用此入口）
            "com/cpq/configure/service/ConfigureSnapshotService.java"
    );

    /** 白名单文件内 open( 出现的总次数——多一处/少一处都值得人工复核，充当额外的绊线。 */
    private static final int EXPECTED_TOTAL_OCCURRENCES = 7;

    /** CardSnapshotService 内允许含 open() 的方法名（报价侧权威 set 点）。 */
    private static final List<String> CARD_SNAPSHOT_PERMITTED_METHODS = List.of(
            "ensureExcelValues",       // P4：报价 Excel 值分支（与核价 Excel 分支共用同一 for 循环，方法级断言无法区分 if 分支，
                                        // 但该方法本就应该含 open()，故此处仍归入"必须含"一侧，if 分支粒度已由 7.1/7.2 的
                                        // SQL 文本断言 + 代码走查覆盖）
            "refreshQuoteCardValues",  // P2：2-arg 重载（1-arg 只是委托，不含 open 也不违规，见 assertTrue 用"任一重载含"判定）
            "dryRunTokenRows"          // P2：公式 dry-run 预览
    );

    /** CardSnapshotService 内明确禁止含 open() 的方法名（核价侧；与上面清单同文件并存，是本测试要守的关键边界）。 */
    private static final List<String> CARD_SNAPSHOT_FORBIDDEN_METHODS = List.of(
            "precomputeCostingDriverUnion",
            "buildCostingCardValues",
            "snapshotNewLinesCardValues",
            "refreshCostingCardValues",
            "snapshotCostingSideOnly",
            "ensureCardValues"
    );

    // ═══════════════════════ 7.4 主断言：文件级白名单精确匹配 ═══════════════════════

    @Test
    void openCallSites_fileLevelWhitelist_exactMatch() throws IOException {
        Path root = srcMainJavaRoot();
        Map<String, Integer> hits = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk.filter(f -> f.toString().endsWith(".java")).toList();
            for (Path p : javaFiles) {
                String content = Files.readString(p);
                int count = countOccurrences(content, OPEN_CALL);
                if (count > 0) {
                    hits.put(root.relativize(p).toString().replace('\\', '/'), count);
                }
            }
        }

        // 前置非空断言（同 7.3 精神）：若遍历算法本身出错（如 root 定位错），hits 会是空 map，
        // 下面的 assertEquals(WHITELIST_FILES, hits.keySet()) 会直接失败而非"因为没找到所以通过"，
        // 但额外加一条显式非空断言，报错信息更直白，方便排查是"真的 0 处"还是"遍历逻辑本身坏了"。
        assertFalse(hits.isEmpty(),
                "全工程 0 处 QuotePendingScope.open( 调用——说明 T3 接线丢失，或本测试遍历 " + root
                        + " 的逻辑本身出了问题（该目录应确实存在且含 T3 已接线的调用点）");

        assertEquals(WHITELIST_FILES, hits.keySet(),
                "调用 QuotePendingScope.open( 的文件集合必须精确等于白名单。实际命中（文件→次数）：" + hits
                        + "；白名单：" + WHITELIST_FILES
                        + "。多出的文件 = 有人在未授权位置开了 pending 可见域（可能破坏 AC-17）；"
                        + "少了的文件 = 某个报价侧权威 set 点的接线丢失（回归 T3 已修复的 bug）。");

        int totalOccurrences = hits.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(EXPECTED_TOTAL_OCCURRENCES, totalOccurrences,
                "open( 调用总数应为 " + EXPECTED_TOTAL_OCCURRENCES
                        + "（CardSnapshotService×3 + ComponentResource×3 + ConfigureSnapshotService×1，见 backtask.md T3）"
                        + "，实际 " + totalOccurrences + "，命中明细：" + hits
                        + "。数量变化本身不代表一定错，但意味着有人新增/删除了调用点，需要人工复核是否仍满足"
                        + "「核价侧一律不调用 open()」这条不变式，并同步更新本测试的期望值与说明。");
    }

    // ═══════════════ 7.4 精细断言：CardSnapshotService 方法体级正/反双向检查 ═══════════════

    @Test
    void cardSnapshotService_quoteSideMethods_containOpenCall() throws IOException {
        Path file = srcMainJavaRoot().resolve("com/cpq/quotation/service/CardSnapshotService.java");
        String raw = Files.readString(file);
        String masked = maskJava(raw);

        for (String name : CARD_SNAPSHOT_PERMITTED_METHODS) {
            List<String> bodies = extractMethodBodies(raw, masked, name);
            assertFalse(bodies.isEmpty(),
                    "方法 " + name + " 在 CardSnapshotService.java 中未提取到任何声明体——" +
                            "说明方法已改名/被删，或本测试的方法体提取逻辑本身失效；此断言必须先在这里失败，" +
                            "否则下面「必须含 open()」的检查会在空列表上对 anyMatch 恒假而被 assertTrue 正确拦截，" +
                            "但为了报错信息更直白，专门先断言一次非空。");
            boolean anyContainsOpen = bodies.stream().anyMatch(b -> b.contains(OPEN_CALL));
            assertTrue(anyContainsOpen,
                    "方法 " + name + "（报价侧权威 set 点之一，见 backtask T3）的全部 " + bodies.size()
                            + " 个重载体内均未找到 QuotePendingScope.open(——T3 的接线丢失或被回退。");
        }
    }

    @Test
    void cardSnapshotService_costingSideMethods_neverContainOpenCall() throws IOException {
        Path file = srcMainJavaRoot().resolve("com/cpq/quotation/service/CardSnapshotService.java");
        String raw = Files.readString(file);
        String masked = maskJava(raw);

        for (String name : CARD_SNAPSHOT_FORBIDDEN_METHODS) {
            List<String> bodies = extractMethodBodies(raw, masked, name);
            assertFalse(bodies.isEmpty(),
                    "方法 " + name + "（核价侧）在 CardSnapshotService.java 中未提取到任何声明体——" +
                            "说明方法已改名/被删，或提取逻辑本身失效。此断言必须先在这里失败，避免下面" +
                            "「禁止含 open()」的检查在空列表上空转通过——这正是本任务要杜绝的" +
                            "「什么都没检查就报绿」的反模式（同 SqlViewExecutorPendingHookTest 的教训）。");
            for (String body : bodies) {
                assertFalse(body.contains(OPEN_CALL),
                        "核价侧方法 " + name + " 的某个重载体内出现了 QuotePendingScope.open(——" +
                                "这会让核价侧误开 pending 可见域，直接破坏 AC-17（静默改写出 pending 数据 + __v6_id，" +
                                "不报错不崩溃，只会在等价性比对里才会暴露）。方法体片段（前 300 字符）：\n"
                                + body.substring(0, Math.min(300, body.length())));
            }
        }
    }

    // ═══════════════════════════════ 工具方法 ═══════════════════════════════

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 从当前工作目录向上查找 {@code cpq-backend/src/main/java}（或当前已在 cpq-backend 模块内时的
     * {@code src/main/java}），不依赖测试执行时的具体 cwd（Maven 模块内跑 / 仓库根跑均可定位）。
     */
    private static Path srcMainJavaRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("cpq-backend/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            if ("cpq-backend".equals(String.valueOf(dir.getFileName()))) {
                candidate = dir.resolve("src/main/java");
                if (Files.isDirectory(candidate)) return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "无法从当前工作目录定位 cpq-backend/src/main/java：cwd=" + Paths.get("").toAbsolutePath());
    }

    /**
     * 极简 Java 词法屏蔽：把字符串字面量 / 字符字面量 / {@code //} 行注释 / {@code /* *}{@code /} 块注释
     * 替换为等长空白（换行符原样保留，长度与 {@code src} 完全对齐），供括号深度扫描定位方法体用——
     * 避免日志字符串或注释里出现的 {@code '{'}/{@code '}'} 干扰括号计数（本文件里确实存在，例如
     * SQL 拼接字符串、日志格式串）。与 {@link SqlTextMask#mask} 同思路，语法规则换成 Java。
     */
    private static String maskJava(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                out.append(' ');
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(d == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(d == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i + 1 < n) {
                    out.append("  ");
                    i += 2;
                } else {
                    i = n;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 从 {@code masked}（已词法屏蔽的源码，长度/换行与 {@code raw} 完全对齐）里找出所有名为
     * {@code methodName} 的方法<b>声明</b>（含全部重载），区分声明与调用的判据：形参列表的
     * 匹配右括号 {@code )} 之后（跳过空白）紧跟 {@code {} 的才是声明（方法体开始）；调用后面
     * 通常是 {@code ;}/{@code ,}/{@code .}/运算符等。对每个声明用括号深度计数取出方法体，
     * 返回体文本片段列表（取自 {@code raw}，保留原文用于子串检索）。
     *
     * <p>不依赖硬编码行号——纯粹按方法名 + 括号结构定位，方法在文件内的位置漂移不影响结果。
     */
    private static List<String> extractMethodBodies(String raw, String masked, String methodName) {
        List<String> bodies = new ArrayList<>();
        Pattern p = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
        Matcher m = p.matcher(masked);
        int searchFrom = 0;
        while (searchFrom <= masked.length() && m.find(searchFrom)) {
            int parenOpen = masked.indexOf('(', m.start());
            int depth = 1;
            int i = parenOpen + 1;
            while (i < masked.length() && depth > 0) {
                char c = masked.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                i++;
            }
            int afterParen = i; // 紧跟在匹配的 ')' 之后
            int j = afterParen;
            while (j < masked.length() && Character.isWhitespace(masked.charAt(j))) j++;
            if (j < masked.length() && masked.charAt(j) == '{') {
                // 声明：从这个 '{' 开始按深度计数取方法体
                int braceStart = j;
                int bd = 1;
                int k = braceStart + 1;
                while (k < masked.length() && bd > 0) {
                    char c = masked.charAt(k);
                    if (c == '{') bd++;
                    else if (c == '}') bd--;
                    k++;
                }
                int braceEnd = k; // 紧跟在匹配的 '}' 之后
                bodies.add(raw.substring(braceStart, braceEnd));
                searchFrom = braceEnd;
            } else {
                // 调用，不是声明；从匹配右括号之后继续找下一处
                searchFrom = afterParen;
            }
        }
        return bodies;
    }
}
