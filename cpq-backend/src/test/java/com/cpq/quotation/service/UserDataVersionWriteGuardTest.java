package com.cpq.quotation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 B-5d — <b>AC-13 的守卫</b>：{@code quotation.user_data_version} 只能被
 * 「用户数据写入」路径递增，派生数据写入路径一个字节都不许碰。
 *
 * <h3>为什么用源码扫描而不是行为测试</h3>
 * AC-13 要防的是<b>未来某次改动顺手加了一行</b>——比如有人在 {@code ensureCardValues} 里
 * "顺便把版本号也更新一下"。行为测试只能覆盖今天存在的调用路径，覆盖不了明天新增的；
 * 而这条一旦被违反，后果是<b>用户什么都不做也会被反复要求刷新</b>
 * （保存 → 重算 → 必冲突 → 刷新 → 保存，死循环），且在功能测试里表现为「偶发 409」，极难定位。
 *
 * <p>所以这里钉死「谁可以写这一列」。新增合法写点时，<b>请连同 api.md §4.1 一起更新</b>，
 * 而不是直接把文件名加进白名单——白名单变长本身就是需要复核的信号。
 *
 * <p>纯 JUnit，不需要数据库。
 */
@DisplayName("UserDataVersionWriteGuardTest — user_data_version 的写入口白名单（AC-13）")
class UserDataVersionWriteGuardTest {

    /** 允许出现 `user_data_version` 写语句的文件（相对 src/main/java）。 */
    private static final List<String> WRITE_ALLOWLIST = List.of(
            // 唯一的自增方法 bumpUserDataVersion（saveDraft 调它）
            "com/cpq/quotation/service/QuotationService.java",
            // quote-card-edit：写 row_data ＝ 用户数据，按 api.md §2 必须递增并回传
            "com/cpq/quotation/service/CardSnapshotService.java"
    );

    /** 明确点名的派生数据写入方——AC-13 / api.md §4.2 列的那几个，一个都不许写这一列。 */
    private static final List<String> DERIVED_WRITERS_MUST_NOT_TOUCH = List.of(
            "com/cpq/configure/service/ConfigureSnapshotService.java",       // snapshotQuotation
            "com/cpq/basicdata/v6/service/CreateQuotationMaterializer.java", // 建单物化四步
            "com/cpq/priceadjust/service/PriceReconciler.java"               // priceReconcile
    );

    @Test
    @DisplayName("只有白名单文件可以写 user_data_version")
    void onlyAllowlistedFilesWriteTheColumn() throws IOException {
        Path root = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(root), "找不到 src/main/java —— 本测试必须在模块根目录下运行");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                if (!writesUserDataVersion(src)) continue;
                if (!WRITE_ALLOWLIST.contains(rel)) offenders.add(rel);
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下文件出现了对 user_data_version 的写入，但不在白名单里：" + offenders
              + "\n若这是一次「用户数据写入」，请连同 api.md §4.1 一起更新后再加白名单；"
              + "\n若这是派生数据写入（卡片值/Excel 值/快照/物化/归位），请删掉那行——"
              + "它会让用户什么都没做就撞 409，形成保存→重算→必冲突→刷新死循环（AC-13）。");
    }

    @Test
    @DisplayName("派生数据写入方（快照/物化/归位）完全不提 user_data_version")
    void derivedWritersNeverTouchTheColumn() throws IOException {
        Path root = Paths.get("src/main/java");
        List<String> offenders = new ArrayList<>();
        for (String rel : DERIVED_WRITERS_MUST_NOT_TOUCH) {
            Path f = root.resolve(rel);
            assertTrue(Files.exists(f), "白名单里的文件不存在（是不是改名了？）：" + rel);
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (src.contains("user_data_version") || src.contains("userDataVersion")) offenders.add(rel);
        }
        assertTrue(offenders.isEmpty(),
                "派生数据写入方提到了 user_data_version（哪怕只是读）：" + offenders
              + "\n它们不该关心这个版本号——见 api.md §4.2 / 需求文档 AC-13。");
    }

    /**
     * 只认<b>真正能落库</b>的写：原生 SQL 的 {@code SET user_data_version}。
     *
     * <p>Java 侧对 {@code Quotation.userDataVersion} 的赋值一律<b>不算</b>写——该字段映射为
     * {@code insertable=false, updatable=false}，Hibernate 永远不会把它写进 UPDATE 语句。
     * 这一点由 {@link #entityColumnStaysReadOnly()} 单独钉死；两条测试合起来才构成完整保证：
     * 「Java 路彻底堵死」+「SQL 路只有白名单文件能走」。
     */
    private static boolean writesUserDataVersion(String src) {
        return src.contains("SET user_data_version");
    }

    /** 去掉块注释与行注释——注释里引用这条 SQL 是正常的（本任务到处在解释它），不该算成写入。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("实体列必须保持只读映射（insertable=false, updatable=false）")
    void entityColumnStaysReadOnly() throws IOException {
        Path entity = Paths.get("src/main/java/com/cpq/quotation/entity/Quotation.java");
        String src = Files.readString(entity, StandardCharsets.UTF_8);
        assertTrue(src.contains("@Column(name = \"user_data_version\", insertable = false, updatable = false)"),
                "Quotation.userDataVersion 必须是只读映射。\n"
              + "Quotation 实体没有 @DynamicUpdate：一旦这一列可写，任何碰过该实体的事务都会发全列 UPDATE，"
              + "把事务开始时读到的旧版本号写回去——版本号会被 ensureCardValues / recomputeDraftHeaderTotals "
              + "这类慢派生路径静默<b>倒退</b>，用户随后正常保存反而撞 409（AC-13 要防的正是这个）。");
    }
}
