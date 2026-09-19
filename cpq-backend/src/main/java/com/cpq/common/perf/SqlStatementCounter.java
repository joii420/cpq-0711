package com.cpq.common.perf;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * repair-260918 B-15 · per-thread counter of SQL statements prepared through Hibernate
 * (HQL / Criteria / native queries / entity flushes of the default persistence unit).
 *
 * <p>Usage: {@code long before = SqlStatementCounter.current(); ... ; long sql = SqlStatementCounter.current() - before;}
 * The counter is monotonic per thread, so nested measurements do not interfere with each other.
 *
 * <p>Scope of the number: statements prepared by Hibernate on the current thread. Raw JDBC that bypasses
 * Hibernate (e.g. a separately injected {@code DataSource}) is not counted. The inspector never rewrites SQL.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class SqlStatementCounter implements StatementInspector {

    private static final ThreadLocal<long[]> COUNT = ThreadLocal.withInitial(() -> new long[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    /** Monotonic number of statements prepared by Hibernate on the current thread. */
    public static long current() {
        return COUNT.get()[0];
    }
}
