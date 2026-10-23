package com.seatrush.order;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager with no resource behind it, for the unit tests of the checkout.
 *
 * <p>The contract the checkout relies on is Spring's, not a database's: a transaction is active
 * while the four writes happen, it commits once, and it rolls back when one of them throws. That is
 * observable without a database and is what these tests assert. Whether the rollback really undid
 * anything is a question for {@code CheckoutIT}.
 */
public final class DirectTransactionManager extends AbstractPlatformTransactionManager {

  private int commits;
  private int rollbacks;

  public int commits() {
    return commits;
  }

  public int rollbacks() {
    return rollbacks;
  }

  @Override
  protected Object doGetTransaction() {
    // Not null, which is what makes isActualTransactionActive() true inside the callback.
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
