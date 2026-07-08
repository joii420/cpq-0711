package com.cpq.configure;

import com.cpq.configure.service.SalesSignatureRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TDD for Plan 3b Task 1: sel_part_signature 表 + SalesSignatureRepository。
 *
 * 验证:
 *  - 唯一键 (customer_no, structure_version, config_fingerprint) 拦重复:
 *    第二次 insertOrReadExisting 回读首次的 quote_part_no（模拟并发败者）。
 *  - 不同 fingerprint / 不同 customerNo 各自独立登记。
 *  - lookup 未命中返回 null。
 */
@QuarkusTest
public class SalesSignatureRepositoryTest {

    private static final String PREFIX = "T3BTEST_";
    private static final String STRUCTURE_VERSION = "v1";

    @Inject
    SalesSignatureRepository repo;

    @Inject
    EntityManager em;

    @AfterEach
    @jakarta.transaction.Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM sel_part_signature WHERE customer_no LIKE :p")
                .setParameter("p", PREFIX + "%")
                .executeUpdate();
    }

    @Test
    void insertOrReadExisting_concurrentLoser_readsBackWinnerQuotePartNo() {
        String customerNo = PREFIX + "CUST_A";
        String fp = "f".repeat(64);

        String first = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerNo, STRUCTURE_VERSION, fp, "sig-text-1",
                        "QP-0001", "SIMPLE"));
        assertEquals("QP-0001", first);

        // 模拟并发败者：同一 (customerNo, structureVersion, fp) 再次登记，即使携带不同的候选号，
        // 也必须回读首次赢家的 quote_part_no。
        String second = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerNo, STRUCTURE_VERSION, fp, "sig-text-2",
                        "QP-0002", "SIMPLE"));
        assertEquals("QP-0001", second, "并发败者必须回读先赢者的 quote_part_no");

        Long count = QuarkusTransaction.requiringNew().call(() -> {
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM sel_part_signature WHERE customer_no=:c AND structure_version=:v AND config_fingerprint=:f")
                    .setParameter("c", customerNo)
                    .setParameter("v", STRUCTURE_VERSION)
                    .setParameter("f", fp)
                    .getSingleResult();
            return n.longValue();
        });
        assertEquals(1L, count, "表内应只有 1 行，未产生重复登记");

        // 锁死 DO NOTHING 不覆盖存量 text 的不变量：即使败者传入了不同的 config_signature_text，
        // 库内该行仍应保留首次赢家写入的原值。
        String persistedText = QuarkusTransaction.requiringNew().call(() -> {
            List<?> r = em.createNativeQuery(
                    "SELECT config_signature_text FROM sel_part_signature WHERE customer_no=:c AND structure_version=:v AND config_fingerprint=:f")
                    .setParameter("c", customerNo)
                    .setParameter("v", STRUCTURE_VERSION)
                    .setParameter("f", fp)
                    .getResultList();
            return (String) r.get(0);
        });
        assertEquals("sig-text-1", persistedText, "DO NOTHING 不应覆盖存量 config_signature_text");
    }

    @Test
    void insertOrReadExisting_differentFingerprint_registersIndependently() {
        String customerNo = PREFIX + "CUST_B";
        String fp1 = "1".repeat(64);
        String fp2 = "2".repeat(64);

        String r1 = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerNo, STRUCTURE_VERSION, fp1, "sig-1",
                        "QP-1001", "SIMPLE"));
        String r2 = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerNo, STRUCTURE_VERSION, fp2, "sig-2",
                        "QP-1002", "SIMPLE"));

        assertEquals("QP-1001", r1);
        assertEquals("QP-1002", r2);
    }

    @Test
    void insertOrReadExisting_differentCustomer_registersIndependently() {
        String fp = "3".repeat(64);
        String customerA = PREFIX + "CUST_C1";
        String customerB = PREFIX + "CUST_C2";

        String r1 = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerA, STRUCTURE_VERSION, fp, "sig-a",
                        "QP-2001", "SIMPLE"));
        String r2 = QuarkusTransaction.requiringNew().call(() ->
                repo.insertOrReadExisting(customerB, STRUCTURE_VERSION, fp, "sig-b",
                        "QP-2002", "SIMPLE"));

        assertEquals("QP-2001", r1);
        assertEquals("QP-2002", r2, "不同 customerNo 即使 fingerprint 相同也应各自独立登记");
    }

    @Test
    void lookup_notFound_returnsNull() {
        String result = QuarkusTransaction.requiringNew().call(() ->
                repo.lookup(PREFIX + "NOBODY", STRUCTURE_VERSION, "0".repeat(64)));
        assertNull(result);
    }

    @Test
    void lookup_blankArgs_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> repo.lookup(null, STRUCTURE_VERSION, "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.lookup(" ", STRUCTURE_VERSION, "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.lookup(PREFIX + "X", "", "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.lookup(PREFIX + "X", STRUCTURE_VERSION, null));
    }

    @Test
    void insertOrReadExisting_blankArgs_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                repo.insertOrReadExisting(null, STRUCTURE_VERSION, "x", "t", "QP", "SIMPLE"));
        assertThrows(IllegalArgumentException.class, () ->
                repo.insertOrReadExisting(PREFIX + "X", " ", "x", "t", "QP", "SIMPLE"));
        assertThrows(IllegalArgumentException.class, () ->
                repo.insertOrReadExisting(PREFIX + "X", STRUCTURE_VERSION, null, "t", "QP", "SIMPLE"));
        assertThrows(IllegalArgumentException.class, () ->
                repo.insertOrReadExisting(PREFIX + "X", STRUCTURE_VERSION, "x", "t", "", "SIMPLE"));
    }
}
