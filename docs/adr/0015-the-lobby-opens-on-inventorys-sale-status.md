# ADR-0015: The lobby opens on inventory's sale status, never on queue-service's own clock

- **Status**: Accepted
- **Milestone**: M3
- **README**: [§6.2](../../README.md#62-queue-service-servicesqueue-service), [§6.3](../../README.md#63-inventory-service-servicesinventory-service)

## Context

An event carries an `onSaleAt`. queue-service knows it, because it reads the catalog. It could
therefore drain the lobby into the queue the moment its own clock passes that instant.

Opening a sale is not only a moment, though. Inventory has to warm the seat map into Redis before a
hold can be accepted at all, and holds are refused for an event that is not `ON_SALE`. So there is an
interval between "the clock says the sale is open" and "the sale can be bought from", and a lobby
drained inside that interval puts buyers in front of a sale that refuses them.

Clocks also disagree. Two pods of queue-service, or queue-service and inventory, differ by whatever
their hosts differ by.

## Decision

queue-service never decides that a sale is open. It polls inventory's catalog
(`seatrush.queue.catalog-refresh-interval`, one second) and drains the lobby when inventory reports
the event as `ON_SALE`. Inventory moves an event to that status only after the warm-up has written the
seat map (see also ADR-0002 and the sale opener of
[§6.3](../../README.md#63-inventory-service-servicesinventory-service)).

The same rule decides when the waiting room closes: queue-service follows the `SOLD_OUT` **status**
rather than the `EventSoldOut` message, because a status is true for every pod at every moment while a
message consumed once is known only to the pod that was running when it arrived.

If inventory is unreachable, queue-service keeps serving the last answer it got, because the users
waiting are precisely the ones whose sale is about to open.

## Consequences

There is no instant in which a buyer is admitted to a sale that cannot serve them. The ordering is a
consequence of one service owning the transition rather than of two clocks agreeing.

Opening is late by at most one refresh interval, which is a second and which
[§22](../../README.md#22-configuration) documents as exactly that: how late a sale can open. For a
flash on-sale that is acceptable because the lobby is shuffled anyway, so a late opening affects
everybody waiting equally.

Every pod of queue-service reaches the same conclusion from the same source, so a lobby drained by
one pod and a position reported by another cannot disagree about whether the doors are open.

queue-service depends on inventory's catalog being readable. The fallback to the last known answer is
what keeps that from being an outage during the one minute it matters.

## Alternatives considered

**Draining on `onSaleAt` against the local clock.** Rejected for the warm-up interval and for clock
skew. It is the version that works on a laptop and fails on a cluster.

**Consuming a `SaleOpened` event from inventory.** Rejected for the reason the sold-out rule gives: a
message tells the pod that received it, and a pod that started afterwards has no way to ask what it
missed. A status answers the same question for every pod at any time.

**Inventory calling queue-service to open the lobby.** Rejected because it inverts the dependency of
the two services on the hot path and gives inventory a reason to know the waiting room exists.
