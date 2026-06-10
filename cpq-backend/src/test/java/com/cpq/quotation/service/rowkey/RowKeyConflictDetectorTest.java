package com.cpq.quotation.service.rowkey;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RowKeyConflictDetectorTest {

    @Test
    void noDuplicates_returnsEmpty() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("A||1", "A||2", "B||1"));
        assertTrue(r.isEmpty());
    }

    @Test
    void duplicate_reportsIndices() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("A||1", "A||2", "A||1"));
        assertEquals(1, r.size());
        assertEquals("A||1", r.get(0).rowKey());
        assertEquals(List.of(0, 2), r.get(0).rowIndices());
        assertEquals("投料", r.get(0).componentName());
    }

    @Test
    void tripleDuplicate_collectsAllIndices() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("加工", List.of("X", "X", "X"));
        assertEquals(1, r.size());
        assertEquals(List.of(0, 1, 2), r.get(0).rowIndices());
    }

    @Test
    void multipleDistinctDuplicates_reportedSeparately() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("回料", List.of("A", "B", "A", "B"));
        assertEquals(2, r.size());
    }

    @Test
    void blankKey_isSkipped_notTreatedAsDuplicate() {
        // 空 key（无法计算行键的行）不参与冲突判定，避免把"全空"误判为重复。
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("", "", "A"));
        assertTrue(r.isEmpty());
    }
}
