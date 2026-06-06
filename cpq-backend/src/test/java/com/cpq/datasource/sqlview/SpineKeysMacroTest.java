package com.cpq.datasource.sqlview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpineKeysMacroTest {

    @Test
    void noMacro_returnsUnchanged() {
        String sql = "SELECT * FROM v WHERE a = :customerCode";
        assertEquals(sql, SpineKeysMacro.expandForExecution(sql));
        assertFalse(SpineKeysMacro.containsMacro(sql));
    }

    @Test
    void executionForm_expandsToExistsUnnest() {
        String sql = "SELECT * FROM v t WHERE :spineKeys(t.part, t.parent, t.ver)";
        String out = SpineKeysMacro.expandForExecution(sql);
        assertTrue(SpineKeysMacro.containsMacro(sql));
        assertTrue(out.contains("EXISTS"), out);
        assertTrue(out.contains("unnest(:__skP::text[], :__skPP::text[], :__skV::text[])"), out);
        assertTrue(out.contains("(t.part) IS NOT DISTINCT FROM k.p"), out);
        assertTrue(out.contains("(t.parent) IS NOT DISTINCT FROM k.pp"), out);
        assertTrue(out.contains("(t.ver) IS NOT DISTINCT FROM k.v"), out);
        assertFalse(out.contains(":spineKeys"), "原宏 token 必须被消除");
    }

    @Test
    void validationForm_usesEmptyArrayLiterals_noPlaceholders() {
        String sql = "SELECT 1 WHERE :spineKeys(a, b, c)";
        String out = SpineKeysMacro.expandForValidation(sql);
        assertTrue(out.contains("unnest(ARRAY[]::text[], ARRAY[]::text[], ARRAY[]::text[])"), out);
        assertFalse(out.contains(":__sk"), "校验形不能残留命名占位符");
        assertFalse(out.contains(":spineKeys"), out);
    }

    @Test
    void argsWithNestedParensAndCast_balancedCorrectly() {
        String sql = "WHERE :spineKeys(nullif(trim(t.x),''), t.p::text, coalesce(t.v,'V1'))";
        String out = SpineKeysMacro.expandForExecution(sql);
        assertTrue(out.contains("(nullif(trim(t.x),'')) IS NOT DISTINCT FROM k.p"), out);
        assertTrue(out.contains("(t.p::text) IS NOT DISTINCT FROM k.pp"), out);
        assertTrue(out.contains("(coalesce(t.v,'V1')) IS NOT DISTINCT FROM k.v"), out);
    }

    @Test
    void multipleMacroCalls_allExpanded() {
        String sql = "WHERE :spineKeys(a,b,c) OR :spineKeys(d,e,f)";
        String out = SpineKeysMacro.expandForExecution(sql);
        assertFalse(out.contains(":spineKeys"), out);
        assertEquals(2, countOccurrences(out, "EXISTS"), out);
    }

    @Test
    void wrongArgCount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SpineKeysMacro.expandForExecution("WHERE :spineKeys(a, b)"));
        assertThrows(IllegalArgumentException.class,
                () -> SpineKeysMacro.expandForExecution("WHERE :spineKeys(a, b, c, d)"));
    }

    @Test
    void unbalancedParens_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SpineKeysMacro.expandForExecution("WHERE :spineKeys(a, b, c"));
    }

    @Test
    void missingParens_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SpineKeysMacro.expandForExecution("WHERE :spineKeys"));
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
