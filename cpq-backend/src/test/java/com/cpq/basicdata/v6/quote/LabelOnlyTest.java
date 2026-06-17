package com.cpq.basicdata.v6.quote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LabelOnlyTest {

    @Test
    void stripsLeadingNumberAndSeparator() {
        assertEquals("银点类",   MaterialBomMergeHandler.labelOnly("1.银点类"));
        assertEquals("非银点类", MaterialBomMergeHandler.labelOnly("2.非银点类"));
    }

    @Test
    void stripsMultiDigitPrefix() {
        assertEquals("其他类", MaterialBomMergeHandler.labelOnly("10.其他类"));
    }

    @Test
    void stripsNonDotSeparators() {
        assertEquals("回料",   MaterialBomMergeHandler.labelOnly("3、回料"));
        assertEquals("边角料", MaterialBomMergeHandler.labelOnly("4 边角料"));
    }

    @Test
    void keepsPlainChineseAsIs() {
        assertEquals("组成件", MaterialBomMergeHandler.labelOnly("组成件"));
        assertEquals("边角料", MaterialBomMergeHandler.labelOnly("边角料"));
    }

    @Test
    void pureNumberStaysAsIs() {
        // "1" 剥掉数字后无剩余 → 原样返回 "1"（与历史纯数字数据兼容）
        assertEquals("1", MaterialBomMergeHandler.labelOnly("1"));
    }

    @Test
    void nullAndBlank() {
        assertNull(MaterialBomMergeHandler.labelOnly(null));
        assertEquals("", MaterialBomMergeHandler.labelOnly(""));
    }
}
