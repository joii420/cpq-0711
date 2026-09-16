package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-260915 B-10（AC-20）—— 导出端 {@code component_sql_view} 查询的<b>批量化</b>。
 *
 * <p>改动前 {@code ComponentExportService} 在组件循环里逐个
 * {@code ComponentSqlView.list("componentId", c.id)}：导出 86 个组件 = 86 次查库
 * （既有缺陷，非 task-260915 引入，用户裁决本期一并修掉）。
 *
 * <p><b>计数口径</b>：Hibernate {@link Statistics} 按 HQL 串分桶，本测试只统计 HQL 里出现
 * {@code ComponentSqlView} 的那些查询的执行次数 —— 不受同一轮里 {@code Component} 等其它查询干扰。
 * 该口径对<b>改动前后两种实现都成立</b>（两边的 HQL 里都有 {@code ComponentSqlView}），
 * 所以同一份测试可以直接跑在旧实现上做 A/B。
 */
@QuarkusTest
class Task260915ExportSqlViewBatchTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject ComponentExportService exportService;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private final List<UUID> dirsToClean = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        if (dirsToClean.isEmpty()) return;
        utx.begin();
        em.joinTransaction();
        for (UUID dir : dirsToClean) {
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                    + "(SELECT id FROM component WHERE directory_id = :dir)")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dir).executeUpdate();
        }
        utx.commit();
        dirsToClean.clear();
    }

    @Test
    @DisplayName("AC-20：导出针对 component_sql_view 的 SQL 条数与组件数无关（3 个 vs 12 个组件，相等且 ≤2）")
    void exportSqlViewQueryCountIsIndependentOfComponentCount() throws Exception {
        UUID small = newDirectoryWithComponents(3);
        UUID large = newDirectoryWithComponents(12);

        Statistics stat = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stat.setStatisticsEnabled(true);

        long before = countSqlViewQueries(stat);
        ComponentExportBundle b3 = exportService.exportDirectory(small);
        long q3 = countSqlViewQueries(stat) - before;

        before = countSqlViewQueries(stat);
        ComponentExportBundle b12 = exportService.exportDirectory(large);
        long q12 = countSqlViewQueries(stat) - before;

        System.out.println("[AC-20] 导出时针对 component_sql_view 的查询次数: 组件数 3 -> " + q3
                + " ; 组件数 12 -> " + q12);

        // 防空跑：这一轮必须确实抓到查询，否则「0 条」既可能是没有 N+1、也可能是计数口径根本没生效
        assertTrue(q3 >= 1,
                "本轮针对 component_sql_view 的查询计数为 0 —— 两种归因都要排除后才能结案："
                + "① 计数口径错（Statistics 未开启 / HQL 串里不含 ComponentSqlView）；"
                + "② 查询压根没发生（导出没去取视图）。🚫 不许把 0 当作「没有 N+1」。");
        assertEquals(q3, q12,
                "针对 component_sql_view 的查询次数随组件数变化（3 个组件 " + q3
                + " 次 / 12 个组件 " + q12 + " 次）= N+1 未消除");
        assertTrue(q12 <= 2, "导出针对 component_sql_view 发出了 " + q12 + " 条 SQL，超出常数上限 2");

        // 内容侧：批量化不得改变导出结果的形状
        assertEquals(3, b3.components.size());
        assertEquals(12, b12.components.size());
        for (ComponentExportBundle.Item it : b12.components) {
            assertEquals(1, it.sqlViews.size(), "每个夹具组件应各带 1 条视图，批量分组丢了或串了组件");
            assertTrue(it.sqlViews.get(0).sqlViewName.contains(
                    it.code.substring(it.code.lastIndexOf('-') + 1)),
                    "视图被分到了别的组件名下：" + it.code + " -> " + it.sqlViews.get(0).sqlViewName);
        }
    }

    @Test
    @DisplayName("AC-20 / AC-16：空目录导出不发 component_sql_view 查询，且 components 为空数组非 null")
    void emptyDirectoryExportsEmptyArrayAndSkipsQuery() throws Exception {
        UUID empty = newDirectoryWithComponents(0);

        Statistics stat = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stat.setStatisticsEnabled(true);
        long before = countSqlViewQueries(stat);
        ComponentExportBundle b = exportService.exportDirectory(empty);
        long q = countSqlViewQueries(stat) - before;

        System.out.println("[AC-20] 空目录导出针对 component_sql_view 的查询次数 = " + q
                + " ; components = " + M.valueToTree(b.components));
        assertEquals(0, q, "空目录不该发 `in ()` 这条查询（PG 上是语法错误）");
        assertTrue(b.components != null && b.components.isEmpty());
    }

    /** 只统计 HQL 里含 {@code ComponentSqlView} 的查询的执行次数（旧实现与新实现同样适用）。 */
    private long countSqlViewQueries(Statistics stat) {
        long n = 0;
        for (String q : stat.getQueries()) {
            if (q.contains("ComponentSqlView")) {
                n += stat.getQueryStatistics(q).getExecutionCount();
            }
        }
        return n;
    }

    private UUID newDirectoryWithComponents(int n) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", "T260915-N1EXP-" + dir.toString().substring(0, 8))
                .executeUpdate();
        for (int i = 0; i < n; i++) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            Component c = new Component();
            c.code = "T260915-EXP-" + tag;
            c.name = "导出 N+1 夹具" + i;
            c.componentType = "NORMAL";
            c.columnCount = 0;
            c.status = "ACTIVE";
            c.fields = "[]";
            c.formulas = "[]";
            c.excelColumns = "[]";
            c.directoryId = dir;
            c.persist();

            ComponentSqlView v = new ComponentSqlView();
            v.componentId = c.id;
            v.sqlViewName = "builder_exp_" + tag;
            v.sqlTemplate = "SELECT 1";
            v.declaredColumns = "[]";
            v.requiredVariables = new String[0];
            v.scope = "COMPONENT";
            v.status = "ACTIVE";
            v.persist();
        }
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }
}
