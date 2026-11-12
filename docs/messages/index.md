# Message schemas

One JSON Schema per message type of [README section 10.3](../../README.md#103-message-types),
describing the whole envelope as it appears on a topic. Generated from the records by
`make message-docs` and compared against them by `MessageSchemaTest`, so a schema that has
drifted fails the build.

An unknown property is accepted on purpose ([ADR-0007](../adr/0007-json-messages-with-schema-version.md)):
fields are added and consumers ignore what they do not know.

## `inventory.commands`

- [CommitSeats](CommitSeats.json)
- [ReleaseHold](ReleaseHold.json)

## `inventory.replies`

- [SeatsCommitted](SeatsCommitted.json)
- [SeatsCommitFailed](SeatsCommitFailed.json)
- [HoldReleased](HoldReleased.json)

## `payment.commands`

- [AuthorizePayment](AuthorizePayment.json)
- [CapturePayment](CapturePayment.json)
- [VoidPayment](VoidPayment.json)

## `payment.replies`

- [PaymentAuthorized](PaymentAuthorized.json)
- [PaymentCaptured](PaymentCaptured.json)
- [PaymentVoided](PaymentVoided.json)
- [PaymentDeclined](PaymentDeclined.json)

## `inventory.seat-events`

- [SeatsHeld](SeatsHeld.json)
- [SeatsReleased](SeatsReleased.json)
- [SeatsSold](SeatsSold.json)
- [EventSoldOut](EventSoldOut.json)

## `order.events`

- [OrderConfirmed](OrderConfirmed.json)
- [OrderFailed](OrderFailed.json)

