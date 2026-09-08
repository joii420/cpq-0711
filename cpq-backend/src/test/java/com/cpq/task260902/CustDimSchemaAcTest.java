package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907 · AC-3（报价侧全表 DDL 落地）+ AC-4 的「不许静默写 NULL」那一半。</b>
 *
 * <h3>AC-3 原文（需求文档.md 第 3 节）</h3>
 * 断言（<b>不变量口径，不锁行数</b>）：
 * <ol>
 *   <li>所有 ds_quote_* 业务表及其镜像表（_history、以及后续新增的 _record 等同范式表）<b>全部</b>有 customer_no，
 *       判据 SQL 返回 <b>0 行</b>；</li>
 *   <li>每张镜像表与其主表的 customer_no <b>列定义一致</b>（类型 / 可空性 / 默认值逐项相同）；</li>
 *   <li>迁移 success = true（flyway_schema_history 查证）。</li>
 * </ol>
 *
 * <h3>为什么不写死「29 张」「14 对 _history」</h3>
 * 需求文档已记账：核价回填会话的 S-2 要新建 13 张 _record，合并后 ds_quote_% 会变 42 张，
 * 照原文验会得到<b>假红 —— 红的是数字过期，不是隔离坏了</b>。
 * 本类一律动态扫 information_schema，采集到的数字只打印不进断言。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-3 报价侧全表 DDL + AC-4 非空强制")
class CustDimSchemaAcTest extends CustDimBase {

    // ===================== AC-3(1) 判据 SQL 返 0 行 =====================

    @Test
    @DisplayName("AC-3(1)：所有 ds_quote_% 表（含 _history / _record）都有 customer_no —— 判据 SQL 返 0 行")
    void ac3_1_everyQuoteTableHasCustomerNo() {
        List<String> all = dsQuoteTables();
        // 守卫在前：表清单为空时「缺列的表 = 0 张」恒真，是最纯的假绿
        assertFalse(all.isEmpty(), "AC-3(1) 前置：一张 ds_quote_% 表都没扫到，判据恒真。先确认连的是哪个库。");
        System.out.println("[AC-3(1)] 当前 ds_quote_% 表共 " + all.size() + " 张（仅记录，不进断言）");

        List<String> missing = strCol(
                "SELECT t.table_name FROM information_schema.tables t "
                        + "WHERE t.table_schema='public' AND t.table_type='BASE TABLE' "
                        + "  AND t.table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND NOT EXISTS(SELECT 1 FROM information_schema.columns c "
                        + "                 WHERE c.table_schema='public' AND c.table_name=t.table_name "
                        + "                   AND c.column_name='customer_no') "
                        + "ORDER BY 1");

        assertTrue(missing.isEmpty(),
                "AC-3(1)：以下 ds_quote_% 表仍缺 customer_no（共 " + missing.size() + " 张）：" + missing
                        + "\n  这些表上的整组删除仍按单列轴走，客户 A 导入会静默删掉客户 B 的同料号数据。");
    }

    // ===================== AC-3(2) 镜像表与主表列定义逐项一致 =====================

    @Test
    @DisplayName("AC-3(2)：每张镜像表（_history / _record）的 customer_no 列定义与其主表逐项相同"
            + "（data_type / 长度 / 可空性 / 默认值）")
    void ac3_2_mirrorColumnDefinitionMatchesBase() {
        List<String> mirrors = new ArrayList<>();
        for (String t : dsQuoteTables()) {
            if (t.endsWith("_history") || t.endsWith("_record")) {
                mirrors.add(t);
            }
        }
        assertFalse(mirrors.isEmpty(),
                "AC-3(2) 前置：一张镜像表都没扫到，「逐项相同」恒真（空跑）。");

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String mirror : mirrors) {
            String base = mirror.endsWith("_history")
                    ? mirror.substring(0, mirror.length() - "_history".length())
                    : mirror.substring(0, mirror.length() - "_record".length());
            if (!tableExists(base)) {
                problems.add(mirror + " 找不到对应主表 " + base + "（镜像表命名或主表缺失）");
                continue;
            }
            Map<String, String> mDef = customerNoDef(mirror);
            Map<String, String> bDef = customerNoDef(base);
            if (mDef.isEmpty() || bDef.isEmpty()) {
                problems.add(mirror + " / " + base + " 至少一侧没有 customer_no 列 "
                        + "(mirror=" + mDef + ", base=" + bDef + ")");
                continue;
            }
            compared++;
            if (!mDef.equals(bDef)) {
                problems.add(mirror + " 与 " + base + " 的 customer_no 列定义不一致：镜像=" + mDef + " 主表=" + bDef);
            }
        }
        System.out.println("[AC-3(2)] 扫到镜像表 " + mirrors.size() + " 张，逐项比对了 " + compared + " 对");
        assertTrue(compared > 0,
                "AC-3(2)：一对都没比到（全部因缺列/缺主表跳过），本条是空验证。问题清单=" + problems);
        assertTrue(problems.isEmpty(), "AC-3(2)：" + problems);
    }

    private Map<String, String> customerNoDef(String table) {
        List<Object[]> r = rows("SELECT data_type, coalesce(character_maximum_length::text,'-'), "
                + "is_nullable, coalesce(column_default,'-') "
                + "FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                + table + "' AND column_name='customer_no'");
        if (r.isEmpty()) {
            return Map.of();
        }
        Object[] a = r.get(0);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("data_type", String.valueOf(a[0]));
        m.put("length", String.valueOf(a[1]));
        m.put("is_nullable", String.valueOf(a[2]));
        m.put("default", String.valueOf(a[3]));
        return m;
    }

    // ===================== AC-3(3) 迁移 success = true =====================

    @Test
    @DisplayName("AC-3(3)：给 ds_quote_% 加 customer_no 的那批迁移在 flyway_schema_history 里 success = true，"
            + "且库里没有任何 success = false 的记录")
    void ac3_3_migrationSucceeded() {
        long failed = count("SELECT count(*) FROM flyway_schema_history WHERE success = false");
        List<String> failedRows = strCol("SELECT version || ' ' || description "
                + "FROM flyway_schema_history WHERE success = false ORDER BY installed_rank");
        assertEquals(0L, failed, "AC-3(3)：flyway_schema_history 里存在失败的迁移：" + failedRows);

        // 阳性对照：至少要有一条迁移是「本任务这批」——否则「没有失败记录」在迁移根本没跑时也恒真
        String maxVersion = scalar("SELECT max(version::int)::text FROM flyway_schema_history WHERE success");
        System.out.println("[AC-3(3)] 库里最大成功迁移版本 = V" + maxVersion);
        assertTrue(maxVersion != null && Integer.parseInt(maxVersion) > 0,
                "AC-3(3)：flyway_schema_history 里一条成功迁移都没有，本条是空验证");

        // 与 AC-3(1) 联动：列真的在了，才谈得上「迁移成功」
        long tablesWithCol = count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' AND table_name LIKE 'ds\\_quote\\_%'");
        System.out.println("[AC-3(3)] 已带 customer_no 的 ds_quote_% 表 = " + tablesWithCol + " 张（仅记录）");
        assertTrue(tablesWithCol > 0, "AC-3(3)：没有任何 ds_quote_% 表带 customer_no，迁移没生效");
    }

    // ===================== AC-4 的一半：不许静默写 NULL =====================

    @Test
    @DisplayName("AC-4(DDL 侧)：ds_quote_% 的 customer_no 一律 NOT NULL —— "
            + "「任一条走到 writer 时 customer_no 为空必须报错、不许静默写 NULL」在 DDL 层的兜底")
    void ac4_customerNoIsNotNull() {
        List<String> nullable = strCol("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' "
                + "  AND table_name LIKE 'ds\\_quote\\_%' AND is_nullable='YES' ORDER BY 1");
        long total = count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' AND table_name LIKE 'ds\\_quote\\_%'");
        assertTrue(total > 0,
                "AC-4 前置：没有任何 ds_quote_% 表带 customer_no，「全部 NOT NULL」恒真（空跑）");
        System.out.println("[AC-4] 带 customer_no 的 ds_quote_% 表 " + total + " 张，其中可空 " + nullable.size() + " 张");
        assertTrue(nullable.isEmpty(),
                "AC-4：以下表的 customer_no 可空，writer 一旦拿不到客户号就会静默写 NULL 而不报错："
                        + nullable
                        + "\n  用户 A0-1 裁决明写「可以 NOT NULL —— 所有存量行都会有值」。");
    }

    @Test
    @DisplayName("AC-4(存量侧)：库里没有任何 customer_no IS NULL 的 ds_quote_% 行（存量已按 A0-1 回填）")
    void ac4_noNullRowsInStock() {
        List<String> withCol = strCol("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' "
                + "  AND table_name LIKE 'ds\\_quote\\_%' ORDER BY 1");
        assertFalse(withCol.isEmpty(),
                "AC-4(存量) 前置：没有任何 ds_quote_% 表带 customer_no，本条恒真（空跑）");

        // 守卫在前：先证明这些表里确实有行，再谈「没有 NULL 行」
        long totalRows = 0;
        Map<String, Long> nulls = new LinkedHashMap<>();
        for (String t : withCol) {
            totalRows += count("SELECT count(*) FROM " + t);
            long n = count("SELECT count(*) FROM " + t + " WHERE customer_no IS NULL");
            if (n > 0) {
                nulls.put(t, n);
            }
        }
        System.out.println("[AC-4(存量)] " + withCol.size() + " 张表共 " + totalRows + " 行（仅记录，不进断言）");
        assertTrue(totalRows > 0,
                "AC-4(存量)：这些表一行数据都没有，「无 NULL 行」是恒真的空验证 —— "
                        + "存量回填是否生效验不到，先确认连的是哪个库。");
        assertTrue(nulls.isEmpty(),
                "AC-4(存量)：以下表存在 customer_no IS NULL 的行 " + nulls
                        + "\n  失败形态提示：列建了、自检过了、导入也不报错，只是值恒 NULL —— "
                        + "真因通常在 insertAll / archive 两个写入点的列清单里，不在 DDL 上。");
    }
}
