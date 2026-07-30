package com.helpinminutes.api.common;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ensures a scheduled job body runs on at most one instance at a time.
 *
 * Six {@code @Scheduled} methods had no coordination at all. On a single
 * instance that was harmless; on two it means duplicate Razorpay refunds,
 * duplicate auto-cancellations and duplicate support tickets. The refund one is
 * the expensive mistake — it moves real money.
 *
 * Implemented with a Postgres session-level advisory lock rather than ShedLock,
 * to avoid adding a dependency and a table for something this small. The lock is
 * held only for the duration of the job and released in a finally block; if the
 * process dies, Postgres releases it when the connection closes.
 */
@Component
public class SchedulerLock {
  private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

  private final DataSource dataSource;
  private final TransactionTemplate transactions;

  public SchedulerLock(DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.dataSource = dataSource;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs {@code body} while holding the named lock. If another instance already
   * holds it, this returns immediately without running — the next tick will try
   * again, which is the right behaviour for periodic cleanup work.
   */
  public void runExclusively(String lockName, Runnable body) {
    long key = lockKey(lockName);
    Connection connection = null;
    boolean acquired = false;
    try {
      connection = dataSource.getConnection();
      acquired = tryLock(connection, key);
      if (!acquired) {
        log.debug("Skipping {}: another instance holds the lock", lockName);
        return;
      }
      // The job body runs inside a transaction started here, not via an
      // @Transactional annotation on the caller: the caller invokes its own
      // method through this lambda, and Spring's proxy does not intercept
      // self-invocation, so such an annotation would silently do nothing and
      // any @Modifying query would fail with TransactionRequiredException.
      transactions.executeWithoutResult(ignored -> body.run());
    } catch (Exception e) {
      log.error("Scheduled job {} failed", lockName, e);
    } finally {
      if (connection != null) {
        try {
          if (acquired) unlock(connection, key);
        } catch (Exception e) {
          log.warn("Could not release advisory lock for {}", lockName, e);
        }
        try {
          connection.close();
        } catch (Exception ignored) {
          // Returning the connection to the pool is best-effort.
        }
      }
    }
  }

  private boolean tryLock(Connection connection, long key) throws Exception {
    try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private void unlock(Connection connection, long key) throws Exception {
    try (PreparedStatement ps = connection.prepareStatement("select pg_advisory_unlock(?)")) {
      ps.setLong(1, key);
      ps.execute();
    }
  }

  /** Stable 64-bit key derived from the job name. */
  private static long lockKey(String lockName) {
    long hash = 1125899906842597L;
    for (int i = 0; i < lockName.length(); i++) {
      hash = 31 * hash + lockName.charAt(i);
    }
    return hash;
  }
}
