package com.cpq.configure;

import com.cpq.configure.SalesFingerprintCalculator.ElementPct;
import com.cpq.configure.SalesFingerprintCalculator.EnabledParam;
import com.cpq.configure.SalesFingerprintCalculator.Signature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD 测试 — 选配 Plan 3b Task 2: SalesFingerprintCalculator.
 *
 * <p>纯 JUnit（无 DB 依赖），覆盖: 空哨兵语义 / 工序排序集合 / 元素去尾零 / 客户维度隔离 /
 * 结构版本前缀 / COMPOSITE 顺序无关 / 空守卫防坍缩 / 元素多项排序稳定。
 */
class SalesFingerprintCalculatorTest {

    private final SalesFingerprintCalculator calculator = new SalesFingerprintCalculator();

    private EnabledParam material(String materialCode) {
        return new EnabledParam("MATERIAL", materialCode, null, null);
    }

    private EnabledParam element(List<ElementPct> elements) {
        return new EnabledParam("ELEMENT", null, elements, null);
    }

    private EnabledParam process(List<String> processCodes) {
        return new EnabledParam("PROCESS", null, null, processCodes);
    }

    @Test
    void emptyProcessCodesSentinelDiffersFromNoProcessSlot() {
        List<EnabledParam> withEmptyProcess = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18")))),
            process(List.of())
        );
        List<EnabledParam> withoutProcessSlot = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );

        Signature withEmpty = calculator.computeSimple("CUST-001", withEmptyProcess);
        Signature without = calculator.computeSimple("CUST-001", withoutProcessSlot);

        assertTrue(withEmpty.text().contains("PRC=∅"), "启用但未选工序应含显式空哨兵 PRC=∅, 实际: " + withEmpty.text());
        assertNotEquals(withEmpty.hash(), without.hash(), "空哨兵与未启用槽位应产生不同指纹");
    }

    @Test
    void processCodesOrderInsensitive() {
        List<EnabledParam> a = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18")))),
            process(List.of("电镀", "抛光"))
        );
        List<EnabledParam> b = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18")))),
            process(List.of("抛光", "电镀"))
        );

        Signature sigA = calculator.computeSimple("CUST-001", a);
        Signature sigB = calculator.computeSimple("CUST-001", b);

        assertEquals(sigA.hash(), sigB.hash());
    }

    @Test
    void elementPercentageTrailingZerosNormalized() {
        List<EnabledParam> a = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18.0000"))))
        );
        List<EnabledParam> b = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );

        Signature sigA = calculator.computeSimple("CUST-001", a);
        Signature sigB = calculator.computeSimple("CUST-001", b);

        assertEquals(sigA.hash(), sigB.hash());
    }

    @Test
    void customerDimensionIsolatesFingerprint() {
        List<EnabledParam> enabled = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );

        Signature sigCustA = calculator.computeSimple("CUST-A", enabled);
        Signature sigCustB = calculator.computeSimple("CUST-B", enabled);

        assertNotEquals(sigCustA.hash(), sigCustB.hash());
    }

    @Test
    void structureVersionPrefixPresent() {
        List<EnabledParam> enabled = List.of(material("AgNi90"));

        Signature sig = calculator.computeSimple("CUST-001", enabled);

        assertTrue(sig.text().startsWith("v1|CUST="), "text 应以 v1|CUST= 开头, 实际: " + sig.text());
    }

    @Test
    void compositeOrderInsensitive() {
        Signature sigAB = calculator.computeComposite("CUST-001", List.of("A", "B"));
        Signature sigBA = calculator.computeComposite("CUST-001", List.of("B", "A"));

        assertEquals(sigAB.hash(), sigBA.hash());
    }

    @Test
    void computeSimpleRejectsEmptyOrNullEnabled() {
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", null));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", List.of()));
    }

    @Test
    void computeSimpleRejectsBlankOrNullCustomerNo() {
        List<EnabledParam> enabled = List.of(material("AgNi90"));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple(null, enabled));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("", enabled));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("   ", enabled));
    }

    @Test
    void computeCompositeRejectsEmptyOrNullChildren() {
        assertThrows(IllegalArgumentException.class, () -> calculator.computeComposite("CUST-001", null));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeComposite("CUST-001", List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeComposite(null, List.of("A")));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeComposite("", List.of("A")));
    }

    @Test
    void elementsMultiItemOrderStable() {
        List<EnabledParam> shuffled = List.of(
            material("AgNi90"),
            element(List.of(
                new ElementPct("Ni", new BigDecimal("10")),
                new ElementPct("Cr", new BigDecimal("18")),
                new ElementPct("Ag", new BigDecimal("90"))
            ))
        );
        List<EnabledParam> sorted = List.of(
            material("AgNi90"),
            element(List.of(
                new ElementPct("Ag", new BigDecimal("90")),
                new ElementPct("Cr", new BigDecimal("18")),
                new ElementPct("Ni", new BigDecimal("10"))
            ))
        );

        Signature sigShuffled = calculator.computeSimple("CUST-001", shuffled);
        Signature sigSorted = calculator.computeSimple("CUST-001", sorted);

        assertEquals(sigShuffled.hash(), sigSorted.hash());
    }

    @Test
    void unknownParamTypeCodeRejected() {
        List<EnabledParam> enabled = List.of(new EnabledParam("UNKNOWN", "x", null, null));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", enabled));
    }

    @Test
    void materialCodeBlankRendersSentinel() {
        List<EnabledParam> enabled = List.of(material(null));
        Signature sig = calculator.computeSimple("CUST-001", enabled);
        assertTrue(sig.text().contains("MAT=∅"), "materialCode null 应渲染 MAT=∅, 实际: " + sig.text());
    }

    @Test
    void elementsEmptyListRendersSentinel() {
        List<EnabledParam> enabled = List.of(
            material("AgNi90"),
            element(List.of())
        );
        Signature sig = calculator.computeSimple("CUST-001", enabled);
        assertTrue(sig.text().contains("ELE=∅"), "elements 空应渲染 ELE=∅, 实际: " + sig.text());
    }

    @Test
    void hashIsLowercase64CharHex() {
        List<EnabledParam> enabled = List.of(material("AgNi90"));
        Signature sig = calculator.computeSimple("CUST-001", enabled);
        assertTrue(sig.hash().matches("[0-9a-f]{64}"), "hash 应为小写 64 位 hex, 实际: " + sig.hash());
    }

    // ---- I2: 正向区分度 —— 不同载荷必须产生不同指纹 ----

    @Test
    void differentMaterialCodeProducesDifferentHash() {
        List<EnabledParam> a = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );
        List<EnabledParam> b = List.of(
            material("AgNi70"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );

        Signature sigA = calculator.computeSimple("CUST-001", a);
        Signature sigB = calculator.computeSimple("CUST-001", b);

        assertNotEquals(sigA.hash(), sigB.hash());
    }

    @Test
    void differentElementPercentageProducesDifferentHash() {
        List<EnabledParam> a = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("18"))))
        );
        List<EnabledParam> b = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", new BigDecimal("20"))))
        );

        Signature sigA = calculator.computeSimple("CUST-001", a);
        Signature sigB = calculator.computeSimple("CUST-001", b);

        assertNotEquals(sigA.hash(), sigB.hash());
    }

    // ---- M1: null pct 归一化 ----

    @Test
    void nullElementPctNormalizesToZero() {
        List<EnabledParam> enabled = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr", null)))
        );

        Signature sig = calculator.computeSimple("CUST-001", enabled);

        assertTrue(sig.text().contains("Cr:0"), "null pct 应归一化为 Cr:0, 实际: " + sig.text());
    }

    // ---- I1: 分隔符碰撞 fail-fast 守卫 ----

    @Test
    void processCodeContainingCommaTriggersDelimiterCollisionRejected() {
        // "a","b,c" vs "a,b","c" 若不校验都会渲染成 PRC=a,b,c —— 必须 fail-fast 而非静默碰撞
        List<EnabledParam> enabled = List.of(
            material("AgNi90"),
            process(List.of("a", "b,c"))
        );

        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", enabled));
    }

    @Test
    void customerNoContainingPipeRejected() {
        List<EnabledParam> enabled = List.of(material("AgNi90"));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST|001", enabled));
    }

    @Test
    void materialCodeContainingEqualsRejected() {
        List<EnabledParam> enabled = List.of(material("Ag=Ni90"));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", enabled));
    }

    @Test
    void elementCodeContainingColonRejected() {
        List<EnabledParam> enabled = List.of(
            material("AgNi90"),
            element(List.of(new ElementPct("Cr:X", new BigDecimal("18"))))
        );
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", enabled));
    }

    @Test
    void codeContainingSentinelCharacterRejected() {
        List<EnabledParam> enabled = List.of(material("Ag∅Ni90"));
        assertThrows(IllegalArgumentException.class, () -> calculator.computeSimple("CUST-001", enabled));
    }

    @Test
    void childQuotePartNoContainingCommaRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> calculator.computeComposite("CUST-001", List.of("A,B", "C")));
    }

    @Test
    void compositeCustomerNoContainingDelimiterRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> calculator.computeComposite("CUST=001", List.of("A", "B")));
    }
}
