package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-D 等价护栏：证明 {@link MaterialNoResolver} 把"每次生成都重锁+重读 9 字头 MAX"改为
 * "首次生成锁内读一次 MAX、缓存 {@link BatchState#dbMax}"后，生成的料号序列与原逻辑逐位一致——
 * 即同一 BatchState 内连续生成 = MAX+1, +2, +3 连号，且同名复用缓存返同号。
 *
 * <p>等价依据：advisory xact lock 持有到提交 → 锁后 9 字头 MAX 在本事务内稳定，原逻辑每次重读拿到的就是
 * 同一个值，故缓存与重读结果相同。resolver 只读不写 material_master（生成的字符串由 handler 落库）→ 本测试
 * 不污染数据、无需清理。
 */
@QuarkusTest
class MaterialNoResolverBatchGenTest {

    @Inject MaterialNoResolver resolver;

    // 极不可能预存在于料号表的名称 → findFirstByMaterialName 必 miss → 必走生成路径
    static final String NA = "__P2D_GEN_TEST_AAA__";
    static final String NB = "__P2D_GEN_TEST_BBB__";
    static final String NC = "__P2D_GEN_TEST_CCC__";

    @Test
    @Transactional
    void contiguousGeneration_dbMaxCached() {
        BatchState batch = new BatchState();
        String n1 = resolver.resolve(null, NA, batch);
        String n2 = resolver.resolve(null, NB, batch);
        String n3 = resolver.resolve(null, NC, batch);

        // 形如 9 字头、恰 10 位
        assertTrue(n1.matches("9\\d{9}"), "n1 应为 9 字头 10 位: " + n1);
        // 连号（缓存 dbMax + batchMaxGenerated 自增，与逐次重读 MAX 结果一致）
        assertEquals(Long.parseLong(n1) + 1, Long.parseLong(n2), "n2 应=n1+1");
        assertEquals(Long.parseLong(n2) + 1, Long.parseLong(n3), "n3 应=n2+1");

        // 同名复用缓存 → 同号，不再生成
        assertEquals(n1, resolver.resolve(null, NA, batch), "同名应命中 nameToNo 缓存返同号");
        // 新名继续连号（仍只用缓存 dbMax，不重读）
        String n4 = resolver.resolve(null, "__P2D_GEN_TEST_DDD__", batch);
        assertEquals(Long.parseLong(n3) + 1, Long.parseLong(n4), "n4 应=n3+1");

        // 料号有值时直接返回、不触发生成
        assertEquals("ABC123", resolver.resolve("ABC123", "任意名", batch), "料号有值直接返回");
    }
}
