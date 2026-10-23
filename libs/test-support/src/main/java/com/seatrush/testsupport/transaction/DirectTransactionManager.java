package com.seatrush.testsupport.transaction;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager with no resource behind it, for the unit tests of code that owns a
 * transaction boundary.
 *
 * <p>The contract most of this system relies on is Spring's, not a database's: a transaction is
 * active while a handful of writes happen, it commits once, and it rolls back when one of them
 * throws. All of that is observable without a database, and it is what the unit tests of the outbox
 * and of the SAGA steps assert. Whether the rollback really undid anything is a question only an
 * integration test against Postgres can answer.
 *
 * <p>Here rather than in each module that needs one, for the same reason {@link
 * com.seatrush.testsupport.time.MutableClock} is: it is a fixture every service wants and a copy
 * per service is a copy that drifts.
 */
public final class DirectTransactionManager extends AbstractPlatformTransactionManager {

  private int commits;
  private int rollbacks;

  /** How many transactions committed. */
  public int commits() {
    return commits;
  }

  /** How many rolled back, which is one per callback that threw. */
  public int rollbacks() {
    return rollbacks;
  }

  @Override
  protected Object doGetTransaction() {
    // Not null, which is what makes isActualTransactionActive() true inside the callback. The
    // ports of seatrush-messaging check exactly that before they touch anything.
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {}

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    commits++;
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    rollbacks++;
  }
}
