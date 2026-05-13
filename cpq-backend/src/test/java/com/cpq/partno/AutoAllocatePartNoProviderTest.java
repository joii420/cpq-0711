package com.cpq.partno;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoAllocatePartNoProviderTest {

    @Inject
    AutoAllocatePartNoProvider provider;

    private static final Pattern AGCU_FORMAT = Pattern.compile("^CFG-AgCu-\\d{6}$");
    private static final Pattern AGNI_FORMAT = Pattern.compile("^CFG-AgNi-\\d{6}$");

    @Test
    void apply_returnsExpectedFormat() {
        String pn = provider.apply(new PartNoContext("AgCu", "SIMPLE", UUID.randomUUID()));
        assertTrue(AGCU_FORMAT.matcher(pn).matches(),
            "format mismatch (expected CFG-AgCu-NNNNNN, 6 digits 0-padded): " + pn);
    }

    @Test
    void apply_concurrent10Threads_allUnique() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return provider.apply(new PartNoContext("AgNi", "SIMPLE", UUID.randomUUID()));
            }));
        }
        start.countDown();

        Set<String> seen = new HashSet<>();
        for (Future<String> f : futures) {
            String pn = f.get(15, TimeUnit.SECONDS);
            assertTrue(AGNI_FORMAT.matcher(pn).matches(), "format mismatch: " + pn);
            seen.add(pn);
        }
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        assertEquals(n, seen.size(),
            "expected " + n + " unique part numbers, got duplicates: " + seen);
    }

    @Test
    void apply_nullContext_throws() {
        assertThrows(IllegalArgumentException.class, () -> provider.apply(null),
            "null context should throw IllegalArgumentException");
    }

    @Test
    void apply_blankSymbol_throws() {
        UUID op = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> provider.apply(new PartNoContext("", "SIMPLE", op)),
            "empty symbol should throw");
        assertThrows(IllegalArgumentException.class,
            () -> provider.apply(new PartNoContext("   ", "SIMPLE", op)),
            "whitespace-only symbol should throw");
    }
}
