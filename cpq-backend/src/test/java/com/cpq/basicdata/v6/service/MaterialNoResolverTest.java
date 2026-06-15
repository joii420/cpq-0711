package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialNoResolverTest {

    @Inject MaterialNoResolver resolver;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'TESTRES%'")
          .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    void materialNoPresent_returnedAsIs() {
        var st = new MaterialNoResolver.BatchState();
        assertEquals("ABC123", resolver.resolve("ABC123", null, st));
        assertEquals("ABC123", resolver.resolve("  ABC123  ", "ignored", st), "trim 后返回");
    }

    @Test
    @Transactional
    void nameMatchesExisting_returnsExistingNo() {
        repo.upsertByMaterialNo("9000000007", "TESTRES-MATCH", null, null, null, "3", null, null, null, null);
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000007", resolver.resolve(null, "TESTRES-MATCH", st));
    }

    @Test
    @Transactional
    void nameNotMatched_emptyTable_generatesFirst() {
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000000", resolver.resolve(null, "TESTRES-NEW", st), "空表首个生成 9000000000");
    }

    @Test
    @Transactional
    void increment_respectsExistingNineLeading() {
        repo.upsertByMaterialNo("9000000005", "TESTRES-SEED", null, null, null, "3", null, null, null, null);
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000006", resolver.resolve(null, "TESTRES-INC", st));
    }

    @Test
    @Transactional
    void sameBatchSameName_reusesOneNumber() {
        var st = new MaterialNoResolver.BatchState();
        String a = resolver.resolve(null, "TESTRES-DUP", st);
        String b = resolver.resolve(null, "TESTRES-DUP", st);
        assertEquals(a, b, "同批同名只生成一个（决策 #3）");
    }

    @Test
    @Transactional
    void sameBatchDifferentNames_incrementWithoutWritebackVisibility() {
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000000", resolver.resolve(null, "TESTRES-A", st));
        assertEquals("9000000001", resolver.resolve(null, "TESTRES-B", st));
        assertEquals("9000000002", resolver.resolve(null, "TESTRES-C", st));
    }

    @Test
    void bothBlank_throws() {
        var st = new MaterialNoResolver.BatchState();
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve("  ", "  ", st));
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve(null, null, st));
    }
}
