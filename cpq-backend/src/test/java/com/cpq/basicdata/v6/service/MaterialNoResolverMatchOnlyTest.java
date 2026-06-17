package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialNoResolverMatchOnlyTest {

    @Inject MaterialNoResolver resolver;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String NAME = "MO-银点-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @Transactional void seed() {
        repo.upsertByMaterialNo("MO-EXIST-1", NAME, null,null,null,"3",null,null,null, null, true);
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    void matchOnly_noValue_returnsTrimmed() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertEquals("X-1", resolver.resolveMatchOnly("  X-1 ", null, s));
    }

    @Test
    void matchOnly_emptyNoMatchedByName() {
        seed();
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertEquals("MO-EXIST-1", resolver.resolveMatchOnly(null, NAME, s));
    }

    @Test
    void matchOnly_emptyNoUnmatched_returnsNull_doesNotGenerate() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertNull(resolver.resolveMatchOnly(null, "MO-从不存在的名字", s), "未命中→null，不生成");
        long gen = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue();
        assertEquals(0L, gen, "match-only 绝不生成 9 字头");
    }

    @Test
    void matchOnly_bothBlank_returnsNull() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertNull(resolver.resolveMatchOnly("  ", "  ", s));
    }
}
