package com.cpq.task260907;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试 Excel 的定位与读取。
 *
 * <p>文件由 {@code dev-docs/task-260907-.../测试数据/gen_fixtures.py} 生成并随任务提交，
 * 构造规则见该脚本头注释与 {@code test.md §0}。
 * 🚩 <b>不在 Java 侧手搓表头</b> —— 表头逐格拷自 task-260902 的建表规范 Excel，
 * 手搓一字之差测出来的红是夹具的错不是实现的错。
 */
final class QuoteFixture {

    static final String MAIN = "T260907-主文件-CUST0004.xlsx";
    static final String CROSS_CUSTOMER = "T260907-跨客户-负例.xlsx";
    static final String BAD_CUSTOMER = "T260907-非法客户-负例.xlsx";
    /** D-19（编号不存在）与 B-15（跨客户）同批报出的组合负例。 */
    static final String COMBINED_NEGATIVE = "T260907-组合负例-D19与B15.xlsx";
    static final String EMPTY_CUSTOMER_PART = "T260907-空客户料号.xlsx";
    static final String EMPTY_SHEET_ANNUAL = "T260907-空sheet-年降系数.xlsx";
    static final String R4_ONE_CELL = "T260907-R4-改一格.xlsx";
    static final String BULK_200 = "T260907-大单量-200.xlsx";
    static final String BULK_1845 = "T260907-大单量-1845.xlsx";

    private QuoteFixture() {
    }

    static Path dir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs")
                    .resolve("task-260907-报价导入建单切ds新表").resolve("测试数据");
            if (Files.isDirectory(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到测试数据目录，cwd=" + Path.of("").toAbsolutePath());
    }

    static byte[] bytes(String name) {
        Path p = dir().resolve(name);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("夹具不存在：" + p
                    + "（先跑 测试数据/gen_fixtures.py 生成）");
        }
        try {
            byte[] b = Files.readAllBytes(p);
            if (b.length == 0) {
                throw new IllegalStateException("夹具是空文件：" + p + " —— 空夹具会让断言空跑");
            }
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
