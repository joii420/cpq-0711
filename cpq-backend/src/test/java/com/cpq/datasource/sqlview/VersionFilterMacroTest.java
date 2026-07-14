package com.cpq.datasource.sqlview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0713 B2 单测（镜像 SpineKeysMacroTest 结构）。
 */
class VersionFilterMacroTest {

    @Test
    void noMacro_returnsUnchanged() {
        String sql = "SELECT * FROM v WHERE a = :customerCode";
        assertEquals(sql, VersionFilterMacro.expandForExecution(sql));
        assertFalse(VersionFilterMacro.containsMacro(sql));
    }

    @Test
    void executionForm_expandsToExistsUnnestOrIsCurrentFallback() {
        String sql = "SELECT * FROM v t WHERE :versionFilter(t.is_current, t.bom_version, t.material_no)";
        String out = VersionFilterMacro.expandForExecution(sql);
        assertTrue(VersionFilterMacro.containsMacro(sql));
        assertTrue(out.contains("EXISTS"), out);
        assertTrue(out.contains("unnest(:__vfPart::text[], :__vfVer::text[])"), out);
        assertTrue(out.contains("(t.material_no) IS NOT DISTINCT FROM k.p"), out);
        assertTrue(out.contains("(t.bom_version) IS NOT DISTINCT FROM k.v"), out);
        assertTrue(out.contains("(t.material_no) <> ALL(:__vfPart::text[])"), out);
        assertTrue(out.contains("(t.is_current)"), out);
        assertFalse(out.contains(":versionFilter"), "原宏 token 必须被消除");
    }

    @Test
    void listingForm_expandsToTrue() {
        String sql = "WHERE :versionFilter(a, b, c)";
        String out = VersionFilterMacro.expandForListing(sql);
        assertEquals("WHERE TRUE", out);
    }

    @Test
    void validationForm_expandsToIsCurrentOnly_noNamedParams() {
        String sql = "WHERE :versionFilter(t.is_current, t.bom_version, t.material_no)";
        String out = VersionFilterMacro.expandForValidation(sql);
        assertEquals("WHERE (t.is_current)", out);
        assertFalse(out.contains(":__vf"), "校验形不能残留命名占位符（不污染 required_variables）");
        assertFalse(out.contains(":versionFilter"), out);
    }

    @Test
    void multipleMacroCalls_allExpanded() {
        String sql = "WHERE :versionFilter(a,b,c) OR :versionFilter(d,e,f)";
        String out = VersionFilterMacro.expandForExecution(sql);
        assertFalse(out.contains(":versionFilter"), out);
        assertEquals(2, countOccurrences(out, "EXISTS"), out);
    }

    @Test
    void wrongArgCount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionFilterMacro.expandForExecution("WHERE :versionFilter(a, b)"));
        assertThrows(IllegalArgumentException.class,
                () -> VersionFilterMacro.expandForExecution("WHERE :versionFilter(a, b, c, d)"));
    }

    @Test
    void unbalancedParens_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionFilterMacro.expandForExecution("WHERE :versionFilter(a, b, c"));
    }

    @Test
    void emptyArg_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionFilterMacro.expandForExecution("WHERE :versionFilter(a, , c)"));
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
