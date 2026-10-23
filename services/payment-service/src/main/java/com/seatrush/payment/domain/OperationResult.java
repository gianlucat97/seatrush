package com.seatrush.payment.domain;

/** What came of one operation. */
public enum OperationResult {

  /** The provider did it. */
  SUCCEEDED,

  /** The provider refused it, which only an authorization can be. */
  DECLINED,

  /** There was nothing to do, which only a void can find. */
  NOOP;

  /** Whether real money moved, or was reserved, as a result of this. */
  public boolean movedMoney() {
    return this == SUCCEEDED;
  }
}
