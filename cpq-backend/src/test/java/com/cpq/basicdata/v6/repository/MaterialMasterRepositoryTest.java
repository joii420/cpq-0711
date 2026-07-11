package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialMasterRepositoryTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String N1 = "TESTMMR-NAME-1";
    static final String NO_A = "TESTMMRA01";
    static final String NO_B = "TESTMMRB02";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'TESTMMR%' OR material_name LIKE 'TESTMMR%'")
          .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    @Transactional
    void findFirstByMaterialName_returnsLowestMaterialNo() {
        repo.upsertByMaterialNo(NO_B, N1, null, null, null, "2", null, null, null, null, null);
        repo.upsertByMaterialNo(NO_A, N1, null, null, null, "2", null, null, null, null, null);
        Optional<MaterialMaster> got = repo.findFirstByMaterialName(N1);
        assertTrue(got.isPresent());
        assertEquals(NO_A, got.get().materialNo, "同名多条取 material_no 升序第一条");
    }

    @Test
    @Transactional
    void upsert_preserveDescriptiveTrue_keepsOldNameAndType() {
        repo.upsertByMaterialNo("TESTMMR900", "OLD-NAME", null, null, null, "1", null, null, null, null, null);
        repo.upsertByMaterialNo("TESTMMR900", "NEW-NAME", null, null, null, "3", null, null, null, null, null, true);
        MaterialMaster m = repo.findByMaterialNo("TESTMMR900").orElseThrow();
        assertEquals("OLD-NAME", m.materialName);
        assertEquals("1", m.materialType);
    }

    @Test
    @Transactional
    void upsert_default10arg_overwritesNameAndType() {
        repo.upsertByMaterialNo("TESTMMR901", "OLD-NAME", null, null, null, "1", null, null, null, null, null);
        repo.upsertByMaterialNo("TESTMMR901", "NEW-NAME", null, null, null, "3", null, null, null, null, null);
        MaterialMaster m = repo.findByMaterialNo("TESTMMR901").orElseThrow();
        assertEquals("NEW-NAME", m.materialName);
        assertEquals("3", m.materialType);
    }

    @Test
    @Transactional
    void maxNineLeading_emptyReturnsBase_andRespectsExisting() {
        assertEquals(8_999_999_999L, repo.maxNineLeadingMaterialNo());
        repo.upsertByMaterialNo("9000000005", "TESTMMR-X", null, null, null, "3", null, null, null, null, null);
        assertEquals(9_000_000_005L, repo.maxNineLeadingMaterialNo());
    }
}
