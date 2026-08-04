package com.cpq.basicdata.v6.quote;

import com.cpq.datasource.sqlview.QuotePendingRewriter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0804：把 annual_discount 纳入「版本化 + pending」体系需登记 7 处，
 * 除 VersionedV6Writer.ALLOWED_TABLES 外漏登记都不会报编译错、只会静默失效。
 * 本测试用反射把每一处钉成可执行断言。
 */
class AnnualDiscountRegistrationPointsTest {

    private static final String T = "annual_discount";

    @SuppressWarnings("unchecked")
    private static <C> C readStatic(Class<?> owner, String fieldName) throws Exception {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (C) f.get(null);
    }

    @Test void point1_versionedWriterAllowedTables() throws Exception {
        Set<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.versioning.VersionedV6Writer"), "ALLOWED_TABLES");
        assertTrue(v.contains(T), "漏登记 → 写入器直接抛「表未登记白名单」");
    }

    @Test void point2_versionedWriterSystemTypeScoped() throws Exception {
        Set<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.versioning.VersionedV6Writer"), "SYSTEM_TYPE_SCOPED");
        assertTrue(v.contains(T), "漏登记 → 护栏失效，将来核价侧接入时跨 QUOTE/PRICING 污染版本号");
    }

    @Test void point3_pendingRewriterWhitelist() {
        assertTrue(QuotePendingRewriter.WHITELIST_TABLES.contains(T),
            "漏登记 → SQL 视图读年降表时 pending 行完全不可见，且拿不到 __v6_id 锚点");
    }

    @Test void point4_quoteTableAxisRegistered() throws Exception {
        Class<?> axis = Class.forName("com.cpq.quotation.service.backfill.QuoteTableAxis");
        List<String> all = readStatic(axis, "ALL_MANAGED_TABLES");
        List<String> scan = readStatic(axis, "SCAN_TABLES");
        assertTrue(all.contains(T), "漏登记 → B5 回填扫不到纯 pending 组");
        assertTrue(scan.contains(T), "漏登记 → pending 行永远转不了正");

        java.lang.reflect.Method of = axis.getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        assertNotNull(of.invoke(null, T), "QuoteTableAxis.of(\"annual_discount\") 必须返回 Spec");
    }

    @Test void point5_commitServicePendingTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.service.V6QuotationCommitService"), "PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 导入记录到报价单的 pending 过户漏这张表，行成孤儿");
    }

    @Test void point6_importServicePendingTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.quote.QuoteImportService"), "PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 重导时上一次 pending 残留不清，行数翻倍");
    }

    @Test void point7_quotationServiceCleanupTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.quotation.service.QuotationService"), "B8_PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 删报价单时年降 pending 行残留");
    }
}
