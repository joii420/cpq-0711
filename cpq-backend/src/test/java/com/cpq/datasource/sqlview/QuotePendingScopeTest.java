package com.cpq.datasource.sqlview;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0725 T2 — {@link QuotePendingScope} 单测（纯 ThreadLocal 逻辑，无 DB）。
 *
 * <p>覆盖 backtask.md T2 验收点 3：
 * <ul>
 *   <li>scope 开 + DRAFT → pendingOwner() 非 null</li>
 *   <li>scope 开 + SUBMITTED/APPROVED/PUBLISHED → null（AC-10 冻结判定）</li>
 *   <li>未开 → null</li>
 *   <li>嵌套 open/restore 正确还原</li>
 *   <li>异常路径下 finally 仍还原（无 ThreadLocal 泄漏）</li>
 * </ul>
 * 以及验收点 7 的 SUBMITTED 专项边界（不可用"B5 已升版所以等价不改写"的推理省掉冻结判定）。
 */
class QuotePendingScopeTest {

    @AfterEach
    void resetScope() {
        // 用 restore(null) 而非 clear()（本类故意不提供 public clear()）强制归零，
        // 防止某条测试异常提前退出导致 ThreadLocal 污染同线程的下一个测试。
        QuotePendingScope.restore(null);
    }

    // ─────────────────── 未打开 → 恒 null ───────────────────

    @Test
    void pendingOwner_neverOpened_returnsNull() {
        assertNull(QuotePendingScope.pendingOwner());
    }

    // ─────────────────── DRAFT → 打开成功 ───────────────────

    @Test
    void open_draftStatus_pendingOwnerNonNull() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            assertEquals(qid, QuotePendingScope.pendingOwner(), "DRAFT + 非空 quotationId 应打开作用域");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    // ─────────────────── 冻结态三态 → 恒 null（AC-10）───────────────────

    @Test
    void open_submittedStatus_pendingOwnerNull() {
        // AC-10 关键边界（backtask T2 验收点 7）：此刻 B5 尚未升版，pending 行仍带
        // pending_quotation_id=本单。不可用"反正 B5 已升版所以改写等价于不改写"的推理省掉冻结判定
        // ——本测试专门验证 open() 自身的冻结判定生效，与 B5 是否已跑无关。
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "SUBMITTED");
        try {
            assertNull(QuotePendingScope.pendingOwner(),
                    "SUBMITTED（冻结态）必须存 null——即便 pending 行此刻仍未被 B5 升版转正");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void open_approvedStatus_pendingOwnerNull() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "APPROVED");
        try {
            assertNull(QuotePendingScope.pendingOwner());
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void open_publishedStatus_pendingOwnerNull() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "PUBLISHED");
        try {
            assertNull(QuotePendingScope.pendingOwner());
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    // ─────────────────── quotationId=null → 恒 null（核价侧典型入参）───────────────────

    @Test
    void open_nullQuotationId_pendingOwnerNull() {
        UUID prev = QuotePendingScope.open(null, "DRAFT");
        try {
            assertNull(QuotePendingScope.pendingOwner(), "quotationId=null 时无论 status 为何都不应打开");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    // ─────────────────── 嵌套 open/restore ───────────────────

    @Test
    void nested_openRestore_correctlyRestoresOuterValue() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        UUID prevOuter = QuotePendingScope.open(outer, "DRAFT");
        try {
            assertEquals(outer, QuotePendingScope.pendingOwner());
            UUID prevInner = QuotePendingScope.open(inner, "DRAFT");
            try {
                assertEquals(inner, QuotePendingScope.pendingOwner(), "内层 open 应覆盖为 inner");
            } finally {
                QuotePendingScope.restore(prevInner);
            }
            assertEquals(outer, QuotePendingScope.pendingOwner(), "内层 restore 后应还原为 outer");
        } finally {
            QuotePendingScope.restore(prevOuter);
        }
        assertNull(QuotePendingScope.pendingOwner(), "外层 restore 后应还原为未打开");
    }

    // ─────────────────── 异常路径下 finally 仍还原 ───────────────────

    @Test
    void openRestore_exceptionInTryBlock_finallyStillRestores() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            assertThrows(RuntimeException.class, () -> {
                try {
                    assertEquals(qid, QuotePendingScope.pendingOwner());
                    throw new RuntimeException("模拟渲染期异常");
                } finally {
                    // 调用方约定：finally 无条件 restore，即便 try 块抛异常。
                    // 此处不 restore prev（留给外层 finally），仅验证异常穿透后线程状态未被破坏。
                }
            });
            // 异常已穿透，但 ThreadLocal 值应保持不变（因为内层没有 restore，模拟"调用方忘记 restore"
            // 的对照组——真正的保护来自本测试外层的 finally restore(prev)，验证 restore 本身健壮）。
            assertEquals(qid, QuotePendingScope.pendingOwner());
        } finally {
            QuotePendingScope.restore(prev);
        }
        assertNull(QuotePendingScope.pendingOwner(), "外层 finally restore(null) 后必须无泄漏");
    }

    @Test
    void openRestore_properTryFinallyPattern_noLeakAfterException() {
        // 正确调用范式的异常路径验证：open 在 try 之外或紧邻 try 之前，finally 内 restore。
        UUID qid = UUID.randomUUID();
        UUID prevBefore = QuotePendingScope.pendingOwner();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            throw new IllegalStateException("模拟展开链路里抛出的业务异常");
        } catch (IllegalStateException expected) {
            // 吞掉，走到下面的断言
        } finally {
            QuotePendingScope.restore(prev);
        }
        assertEquals(prevBefore, QuotePendingScope.pendingOwner(),
                "即便 try 块抛异常，finally 里的 restore 也必须执行，不留 ThreadLocal 泄漏");
    }

    // ─────────────────── cacheTag() ───────────────────

    @Test
    void cacheTag_scopeClosed_returnsEmptyString() {
        assertEquals("", QuotePendingScope.cacheTag());
    }

    @Test
    void cacheTag_scopeOpen_returnsNonEmptyTagContainingQuotationId() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            String tag = QuotePendingScope.cacheTag();
            assertNotEquals("", tag);
            assertTrue(tag.startsWith(":pq"), "cacheTag 格式应为 \":pq<qid无横杠>\"，实际：" + tag);
            assertTrue(tag.contains(qid.toString().replace("-", "")),
                    "cacheTag 应含当前 quotationId（无横杠形式），实际：" + tag);
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void cacheTag_frozenStatus_stillEmptyString() {
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "APPROVED");
        try {
            assertEquals("", QuotePendingScope.cacheTag(), "冻结态 open() 已存 null，cacheTag 必须仍为空串");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }
}
