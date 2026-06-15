package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProcessMasterRepositoryTest {

    @Inject ProcessMasterRepository repo;
    @Inject EntityManager em;

    private static final String TEST_PROCESS_NAME = "电镀_TESTPMR_UNIQUE";

    @Transactional
    void seed() {
        em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'TESTPMR%'").executeUpdate();
        em.createNativeQuery("INSERT INTO process_master (id, process_no, process_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), 'TESTPMR-01', '电镀_TESTPMR_UNIQUE', NOW(), NOW())").executeUpdate();
    }
    @Transactional
    void clean() { em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'TESTPMR%'").executeUpdate(); }
    @BeforeEach void before() { seed(); }
    @AfterEach  void after()  { clean(); }

    @Test
    void findFirstByProcessName_hit() {
        Optional<ProcessMaster> got = repo.findFirstByProcessName(TEST_PROCESS_NAME);
        assertTrue(got.isPresent());
        assertEquals("TESTPMR-01", got.get().processNo);
    }

    @Test
    void findFirstByProcessName_miss() {
        assertTrue(repo.findFirstByProcessName("不存在的工序XYZ").isEmpty());
    }
}
