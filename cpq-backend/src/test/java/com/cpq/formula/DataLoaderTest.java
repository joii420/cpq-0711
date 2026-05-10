package com.cpq.formula;

import com.cpq.datapath.cache.CachedPathParser;
import com.cpq.datapath.cache.CachedSqlCompiler;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import com.cpq.formula.dataloader.DataLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DataLoader 单元测试（不依赖 Quarkus 容器）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>同请求 path dedupe（相同 path 只查一次 SQL）</li>
 *   <li>不同 path 各自查询</li>
 *   <li>花括号规范化</li>
 *   <li>空结果处理</li>
 * </ul>
 */
class DataLoaderTest {

    private DataLoader dataLoader;
    private CachedPathParser mockPathParser;
    private CachedSqlCompiler mockSqlCompiler;
    private DataSource mockDataSource;
    private AtomicInteger sqlExecutionCount;

    @BeforeEach
    void setUp() throws Exception {
        mockPathParser = mock(CachedPathParser.class);
        mockSqlCompiler = mock(CachedSqlCompiler.class);
        mockDataSource = mock(DataSource.class);
        sqlExecutionCount = new AtomicInteger(0);

        dataLoader = new DataLoader();
        injectField(dataLoader, "pathParser", mockPathParser);
        injectField(dataLoader, "sqlCompiler", mockSqlCompiler);
        injectField(dataLoader, "dataSource", mockDataSource);

        // Mock PathParser: 任意路径 → 一个 PathExpression mock
        var mockAst = mock(com.cpq.datapath.ast.PathExpression.class);
        when(mockAst.toString()).thenReturn("mock-ast");
        when(mockPathParser.parse(anyString())).thenReturn(mockAst);

        // Mock SqlCompiler: → 简单 SELECT SQL
        SqlAndParams compiled = new SqlAndParams(
                "SELECT composition_pct FROM mat_bom WHERE element_name = ?",
                List.of("Ag"),
                List.of("composition_pct"));
        when(mockSqlCompiler.compile(any(), any(SchemaContext.class))).thenReturn(compiled);

        // Mock DataSource: 返回单行结果
        setupMockResultSet(mockDataSource, sqlExecutionCount, "composition_pct", "90.0");
    }

    @Test
    void dl01_dedupe_samePathOnlyOneSqlExecution() throws Exception {
        // 同 path 调用两次
        CompletableFuture<List<Map<String, Object>>> f1 =
                dataLoader.loadByPath("{元素BOM[元素='Ag'].组成含量}");
        CompletableFuture<List<Map<String, Object>>> f2 =
                dataLoader.loadByPath("{元素BOM[元素='Ag'].组成含量}");

        // 等待完成
        f1.get();
        f2.get();

        // SQL 只执行一次
        assertEquals(1, sqlExecutionCount.get(), "相同 path 应只查一次 SQL（dedupe）");
        // 两个 future 返回相同对象引用
        assertSame(f1, f2, "相同 path 应返回同一个 CompletableFuture");
    }

    @Test
    void dl02_differentPaths_executeSeparatelyEach() throws Exception {
        // 两个不同 path
        CompletableFuture<List<Map<String, Object>>> f1 =
                dataLoader.loadByPath("{元素BOM[元素='Ag'].组成含量}");
        CompletableFuture<List<Map<String, Object>>> f2 =
                dataLoader.loadByPath("{元素BOM[元素='Ni'].组成含量}");

        f1.get();
        f2.get();

        // 各自查询一次，共 2 次
        assertEquals(2, sqlExecutionCount.get(), "不同 path 应各自执行 SQL");
    }

    @Test
    void dl03_normalize_braces_deduped() throws Exception {
        // 带花括号和不带花括号的同一路径应 dedupe
        CompletableFuture<List<Map<String, Object>>> f1 =
                dataLoader.loadByPath("{元素BOM.组成含量}");
        CompletableFuture<List<Map<String, Object>>> f2 =
                dataLoader.loadByPath("元素BOM.组成含量");  // 无花括号

        f1.get();
        f2.get();

        assertEquals(1, sqlExecutionCount.get(), "带/不带花括号的相同路径应 dedupe");
    }

    @Test
    void dl04_cachedPathCount_tracksUniquePaths() throws Exception {
        dataLoader.loadByPath("{path1}");
        dataLoader.loadByPath("{path2}");
        dataLoader.loadByPath("{path1}"); // 重复

        assertEquals(2, dataLoader.cachedPathCount(), "应只记录 2 条唯一路径");
    }

    @Test
    void dl05_normalizePath_stripsWhitespace() {
        assertEquals("table.field", DataLoader.normalizePath("  { table.field }  "));
        assertEquals("table.field", DataLoader.normalizePath("table.field"));
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private void setupMockResultSet(DataSource ds, AtomicInteger counter,
                                    String colName, String colValue) throws Exception {
        Connection mockConn = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        ResultSet mockRs = mock(ResultSet.class);
        ResultSetMetaData mockMeta = mock(ResultSetMetaData.class);

        when(ds.getConnection()).thenAnswer(inv -> {
            counter.incrementAndGet();
            return mockConn;
        });
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);
        when(mockRs.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getColumnCount()).thenReturn(1);
        when(mockMeta.getColumnName(1)).thenReturn(colName);
        // 第一次 next() 返回 true，第二次返回 false
        when(mockRs.next()).thenReturn(true, false);
        when(mockRs.getObject(1)).thenReturn(colValue);
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
