# 6. Traceability

Every requirement of [chapter 3](03-fr-catalog.md) against three things: the
[README](../../README.md) section that designs it, the decision record that chose that design, and
the test or check that proves it. A requirement with nothing in the third column is a requirement
nothing defends, and [section 6.4](#64-open-gaps) lists the two that are in that position.

`scripts/check-frd.py` verifies that this chapter and the catalogue name the same set of
requirements, in both directions. A requirement added to one and not the other fails CI.

Test names are the real method names, so they can be read as the sentences they were written as.
Paths are relative to the repository root.

---

## 6.1 Requirements to design and proof

### FR-1 Catalogue and sale lifecycle

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-1.1` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 12.2](../../README.md#122-events-and-seat-map) | - | `LayoutGeneratorTest`, `VenueTest`, `AdminEventControllerTest`, `CatalogIT`, `SeatMapIT.aVenueOfTwentyThousandSeatsIsLoadedInOneGo` |
| `FR-1.2` | [section 12.2](../../README.md#122-events-and-seat-map), [section 7](../../README.md#7-domain-model) | - | `EventCatalogServiceTest`, `EventControllerTest`, `CatalogEventResponseTest`, `CatalogIT` |
| `FR-1.3` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 8.4](../../README.md#84-live-seat-map) | - | `SeatMapServiceTest`, `SeatMapIT.theSnapshotReadsTheStatusTheFastPathHolds`, `SeatMapIT.theSnapshotNeverTellsAClientWhoHoldsASeat`, `SeatMapIT.anEventThatNeverOpenedHasASnapshotFromPostgresAlone` |
| `FR-1.4` | [section 7](../../README.md#7-domain-model), [section 12.2](../../README.md#122-events-and-seat-map) | - | `EventStatusTest`, `SaleStatusTest`, `AdminEventControllerTest`, `QueueJoinIT.putsAnEarlyArrivalInTheLobbyAndNowhereElse` |
| `FR-1.5` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service) | [ADR-0015](../adr/0015-the-lobby-opens-on-inventorys-sale-status.md) | `SaleOpenerTest`, `SaleCalendarTest`, `SaleOpeningIT` (all seven), `SeatMapIT.openingASaleLoadsTheWholeVenueIntoRedis` |
| `FR-1.6` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 9.6](../../README.md#96-seat-commit-procedure) | [ADR-0015](../adr/0015-the-lobby-opens-on-inventorys-sale-status.md) | `SaleCloserTest`, `SaleEventTest`, `AdminEventControllerTest`, `SeatCommitIT`, `QueueStreamIT.announcesASoldOutSaleToEverybodyStillWaiting` |

### FR-2 Waiting room and admission

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-2.1` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 8.1](../../README.md#81-lobby-waiting-room-and-admission) | [ADR-0004](../adr/0004-randomized-lobby-and-fifo-queue.md) | `QueueJoinIT.putsAnEarlyArrivalInTheLobbyAndNowhereElse`, `QueueJoinIT.keepsALobbyMemberInTheLobbyWhenTheyAskAgain`, `QueueJoinIT.knowsALobbyMemberHasAPlaceEvenWithoutANumber` |
| `FR-2.2` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service) | [ADR-0004](../adr/0004-randomized-lobby-and-fifo-queue.md) | `LobbyOpenerTest`, `QueueJoinIT.shufflesAWholeLobbyIntoTheQueueWhenTheSaleOpens`, `QueueJoinIT.numbersEverybodyOnceEvenWhenTheOpenerRunsTwice` |
| `FR-2.3` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 8.1](../../README.md#81-lobby-waiting-room-and-admission) | [ADR-0004](../adr/0004-randomized-lobby-and-fifo-queue.md) | `QueueJoinIT.numbersArrivalsAtALiveSaleInOrder`, `QueueJoinIT.movesALobbyMemberOutOfTheLobbyWhenTheyJoinALiveSale`, `QueueJoinIT.sendsAUserWhoLeftToTheBackWhenTheyReturn`, `QueueJoinIT.givesEverySequenceKeyALifetimeSoASaleDoesNotLeakKeysForEver` |
| `FR-2.4` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 12.3](../../README.md#123-waiting-room) | - | `QueueJoinIT.givesBackTheSamePlaceHoweverOftenAUserAsks`, `QueueJoinIT.readsBackAPlaceFromBothSidesOfTheDoor` |
| `FR-2.5` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 22](../../README.md#22-configuration) | - | `AdmissionJobTest`, `AdmissionPropertiesTest`, `QueueAdmissionIT.letsInTheFrontOfTheQueueAndMovesTheHead`, `QueueAdmissionIT.neverLetsMoreThanTheCapIntoThePurchaseAreaAtOnce`, `QueueAdmissionIT.freesExpiredSlotsAndFillsThemInOneStep`, `QueueAdmissionIT.skipsSomebodyWhoLeftAndStillMovesTheHeadPastThem` |
| `FR-2.6` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 12.3](../../README.md#123-waiting-room) | [ADR-0005](../adr/0005-sse-instead-of-websocket.md) | `AdmissionRateEstimatorTest`, `QueueUpdateTest`, `WaitingRoomUpdatesTest`, `QueueStreamTest`, `QueueStreamIT.answersTheFirstPositionOnTheConnectionItself`, `QueueStreamIT.pushesAPositionThatFallsAsTheDoorMoves`, `QueueStreamIT.placesAListenerWhoWasStillInTheLobbyWhenTheyConnected`. The keep-alive comment of criterion 4 is proved on the seat-map stream only, by `SeatMapStreamIT.keepsAQuietStreamAlive`; the queue stream shares the interval and has no test of its own |
| `FR-2.7` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service), [section 13](../../README.md#13-security) | [ADR-0014](../adr/0014-admission-as-a-signed-bearer-token.md), [ADR-0016](../adr/0016-admission-tokens-minted-on-read.md) | `AdmissionTokenTest`, `AdmissionTokenSignerTest`, `QueueAdmissionIT.handsAnAdmittedBuyerATokenThatEndsWithTheirSlot`, `QueueAdmissionIT.stopsHonouringTheTokenTheInstantTheSlotExpires`, `QueueAdmissionIT.handsNoTokenToABuyerWhoseSlotHasLapsed`, `QueueStreamIT.stopsHoldingTheConnectionOfABuyerItHasAdmitted` |
| `FR-2.8` | [section 6.2](../../README.md#62-queue-service-servicesqueue-service) | [ADR-0015](../adr/0015-the-lobby-opens-on-inventorys-sale-status.md) | `WaitingRoomCloserTest`, `QueueSlotReleaseIT` (all five), `QueueStreamIT.dropsTheLobbyAndTheQueueOfASaleThatIsOver` |

### FR-3 Seat holds

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-3.1` | [section 8.2](../../README.md#82-seat-hold), [section 9.1](../../README.md#91-two-layers-of-protection-against-overselling), [section 9.2](../../README.md#92-redis-key-design) | [ADR-0002](../adr/0002-lua-fast-path-postgres-final-guard.md) | `HoldTest`, `HoldControllerTest`, `SeatStateTest`, `HoldIT.aHoldTakesTheSeatsAndMovesTheVersion`, `HoldIT.aHoldIsAllOrNothing`, `HoldIT.tenThousandConcurrentAttemptsOnOneHundredSeatsHoldExactlyOneHundred`, `HoldIT.seatsOfAnotherEventAreARequestError` |
| `FR-3.2` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 9.2](../../README.md#92-redis-key-design) | [ADR-0012](../adr/0012-one-active-hold-per-user-per-event.md) | `HoldIT.aUserHoldsOnlyOneSetOfSeatsPerEvent`, `HoldIT.fiveHundredConcurrentAttemptsByOneUserHoldOneSetOfSeats`, `HoldIT.anExpiredHoldDoesNotBlockTheNextOneOfTheSameUser` |
| `FR-3.3` | [section 12.4](../../README.md#124-holds) | - | `HoldControllerTest`, `HoldIT.releasingGivesTheSeatsBackAndLetsTheUserHoldAgain` |
| `FR-3.4` | [section 9.4](../../README.md#94-idempotency) | - | `HoldIT.theSameRequestTwiceIsOneHold`, `HoldIT.theSameKeyWithOtherSeatsIsRefused` |
| `FR-3.5` | [section 9.3](../../README.md#93-hold-expiry) | [ADR-0008](../adr/0008-hold-expiry-via-sweeper.md) | `HoldIT.theSweeperTakesTheSeatsOfAHoldWhoseTimeRanOut`, `HoldIT.theSweeperLeavesAHoldThatIsStillCountingDown`, `HoldIT.aSecondSweepOfTheSameHoldChangesNothing` |
| `FR-3.6` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 8.3](../../README.md#83-checkout-saga-orchestrated) | [ADR-0013](../adr/0013-synchronous-hold-freeze-at-order-creation.md) | `HoldFreezeServiceTest`, `InventoryHoldFreezerTest`, `HoldFreezeIT`, `CheckoutIT.anExpiredHoldLeavesNothingBehind`, `CheckoutIT.aHoldAnotherOrderOwnsLeavesNothingBehind`, `CheckoutIT.aRepeatedCheckoutTakesOverAFreezeWhoseOrderWasNeverWritten` |
| `FR-3.7` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 16](../../README.md#16-failure-modes), [section 12.7](../../README.md#127-internal-endpoints-not-exposed-by-the-gateway) | - | `InternalEventControllerTest`, `SeatMapIT.aWarmUpReplacesWhateverWasThere`, and `restart-redis` of `scripts/chaos.sh` |

### FR-4 Checkout

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-4.1` | [section 6.4](../../README.md#64-order-service-servicesorder-service), [section 8.3](../../README.md#83-checkout-saga-orchestrated), [section 9.5](../../README.md#95-transactional-outbox-postgres-and-redis) | [ADR-0001](../adr/0001-orchestrated-saga-for-checkout.md), [ADR-0013](../adr/0013-synchronous-hold-freeze-at-order-creation.md) | `StartCheckoutCommandTest`, `CheckoutCommandServiceTest`, `CheckoutServiceTest`, `CheckoutIT.aCheckoutCreatesAnOrderItsSagaAndTheAuthorizationTogether`, `CheckoutIT.theAuthorizationLeavesForKafkaKeyedByTheOrder`, `CheckoutIT.tellsInventoryWhichOrderIsTakingTheHold` |
| `FR-4.2` | [section 9.4](../../README.md#94-idempotency) | - | `CheckoutIT.aRetryAnswersWithTheSameOrderAndDoesNotCallInventoryAgain`, `CheckoutIT.refusesTheSameKeyUsedForADifferentHold`, `CheckoutIT.manyConcurrentRequestsWithOneKeyProduceOneOrder` |
| `FR-4.3` | [section 8.3](../../README.md#83-checkout-saga-orchestrated), [section 9.7](../../README.md#97-saga-persistence) | [ADR-0001](../adr/0001-orchestrated-saga-for-checkout.md), [ADR-0009](../adr/0009-commitseats-as-pivot-transaction.md) | `CheckoutSagaTest`, `SagaInstanceTest`, `SagaIT.aCheckoutRunsToConfirmedOneReplyAtATime` |
| `FR-4.4` | [section 12.5](../../README.md#125-orders), [section 6.1](../../README.md#61-gateway-servicesgateway) | - | `CheckoutIT.aUserReadsTheirOwnOrdersNewestFirstAndPagesThroughThem`, `CheckoutIT.anOrderOfAnotherUserIsNotInThisUsersList`, `GatewayEdgeIT.sendsOrdersToOrderServiceAndHoldsToInventory` |
| `FR-4.5` | [section 9.1](../../README.md#91-two-layers-of-protection-against-overselling), [section 9.6](../../README.md#96-seat-commit-procedure) | [ADR-0002](../adr/0002-lua-fast-path-postgres-final-guard.md), [ADR-0009](../adr/0009-commitseats-as-pivot-transaction.md) | `SeatCommitIT` (all thirteen), in particular `thePostgresGuardRefusesASecondSaleOfASeat`, `aRedeliveredCommitAnswersAgainAndSellsNothingTwice`, `theReconcilerFinishesACommitWhoseLastStepNeverRan` |
| `FR-4.6` | [section 8.3](../../README.md#83-checkout-saga-orchestrated), [section 9.7](../../README.md#97-saga-persistence) | [ADR-0009](../adr/0009-commitseats-as-pivot-transaction.md) | `SagaIT.anUnansweredCaptureIsSentAgain`, `SagaIT.aCaptureThatNeverSucceedsLeavesTheOrderWithItsSeatsSold`, `SagaIT.countsACaptureThatHasUsedUpEveryRetry`, `PaymentIT.theWholeHappyPathOfOneOrder`, and `I3` of the checker |
| `FR-4.7` | [section 8.3](../../README.md#83-checkout-saga-orchestrated) | [ADR-0001](../adr/0001-orchestrated-saga-for-checkout.md), [ADR-0009](../adr/0009-commitseats-as-pivot-transaction.md) | `SagaIT.aDeclinedPaymentReleasesTheHoldAndFails`, `SagaIT.aFailedCommitVoidsAndReleasesBeforeFailing`, `SagaIT.aCompensatingCheckoutWaitsForEveryAcknowledgement`, `SagaIT.aLateAuthorizationIsVoidedAgain`, `PaymentIT.aVoidOfAnOrderThatWasNeverAuthorizedSucceedsAsANoop`, and `I4` of the checker |
| `FR-4.8` | [section 8.3](../../README.md#83-checkout-saga-orchestrated), [section 22](../../README.md#22-configuration) | [ADR-0009](../adr/0009-commitseats-as-pivot-transaction.md) | `SagaIT.theDeadlineIsDroppedOnceTheCommitIsOut`, `SagaIT.aCheckoutNobodyAnswersIsCompensatedByTheDeadline`, `SagaIT.countsTheCheckoutsPastTheirDeadlineUntilTheyAreCompensated`, and `I7` of the checker |

### FR-5 Tickets

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-5.1` | [section 6.4](../../README.md#64-order-service-servicesorder-service), [section 11.2](../../README.md#112-order-database) | - | `TicketIssuerTest`, `TicketRepositoryIT`, `SagaIT.aCheckoutRunsToConfirmedOneReplyAtATime`, and the totals check of the checker |
| `FR-5.2` | [section 13](../../README.md#13-security) | - | `TicketSignerTest`, `TicketKeysTest` |
| `FR-5.3` | [section 12.5](../../README.md#125-orders) | - | `TicketRepositoryIT`, `CheckoutIT.aUserReadsTheirOwnOrdersNewestFirstAndPagesThroughThem` |

### FR-6 Live seat map

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-6.1` | [section 6.6](../../README.md#66-realtime-service-servicesrealtime-service), [section 8.4](../../README.md#84-live-seat-map) | [ADR-0005](../adr/0005-sse-instead-of-websocket.md) | `SeatMapDeltasTest`, `SeatMapStreamsTest`, `SeatMapStreamIT.sendsTheSameDeltaToEveryClientWatchingThatSale`, `SeatMapStreamIT.saysNothingToTheClientsOfAnotherSale`, `SeatMapStreamIT.holdsTheStreamOfASaleItHasNeverHeardOf` |
| `FR-6.2` | [section 6.3](../../README.md#63-inventory-service-servicesinventory-service), [section 9.5](../../README.md#95-transactional-outbox-postgres-and-redis) | [ADR-0011](../adr/0011-redis-streams-as-outbox-for-lua-events.md), [ADR-0003](../adr/0003-transactional-outbox-with-polling-relay.md) | `SeatEventRelayTest`, `SeatEventListenerTest`, `SeatEventRelayIT`, `HoldIT.aHeldSeatIsWrittenIntoTheStreamForTheRelay`, `SeatCommitIT.aCommitTellsTheSeatMapWatchersThatTheSeatsAreSold` |
| `FR-6.3` | [section 6.6](../../README.md#66-realtime-service-servicesrealtime-service), [section 22](../../README.md#22-configuration) | - | `RealtimeConfigurationTest`, `SeatMapStreamIT.sendsEverythingOneWindowAbsorbedAsOneDelta`, `SeatMapStreamIT.sendsOnlyTheLastWordAboutASeatOfThatWindow`, `SeatMapStreamIT.sendsTheLastSeatsOfASoldOutSaleWithoutWaitingForTheWindow` |
| `FR-6.4` | [section 6.6](../../README.md#66-realtime-service-servicesrealtime-service), [section 24.6](../../README.md#246-graceful-shutdown) | [ADR-0005](../adr/0005-sse-instead-of-websocket.md) | `SeatMapStreamIT.greetsAClientWithTheDelayItShouldReconnectAfter`, `SeatMapStreamIT.keepsAQuietStreamAlive`, `SeatMapStreamIT.tellsEveryClientWhenToComeBackAsThisPodGoesAway` |
| `FR-6.5` | [section 6.1](../../README.md#61-gateway-servicesgateway), [section 12.2](../../README.md#122-events-and-seat-map) | - | `GatewayEdgeIT.needsNoAdmissionToWatchASeatMap`, `GatewayEdgeIT.sendsTheSeatMapStreamToRealtimeAndTheSnapshotToInventory` |

### FR-7 Access control and abuse limits

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-7.1` | [section 6.1](../../README.md#61-gateway-servicesgateway), [section 13](../../README.md#13-security) | - | `JwtCodecTest`, `UserTokenTest`, `UserTokenSignerTest`, `SecurityAutoConfigurationTest`, `GatewayEdgeIT.refusesARequestThatCarriesNoToken`, `GatewayEdgeIT.refusesATokenItDidNotSign`, `GatewayEdgeIT.refusesATokenThatHasExpired`, `GatewayEdgeIT.writesTheIdentityHeaderFromTheTokenAndDropsTheOneTheClientSent`, `DemoLoginsTest`. **HS256 only**: see [open gaps](#64-open-gaps) |
| `FR-7.2` | [section 6.1](../../README.md#61-gateway-servicesgateway), [section 13](../../README.md#13-security) | [ADR-0014](../adr/0014-admission-as-a-signed-bearer-token.md), [ADR-0016](../adr/0016-admission-tokens-minted-on-read.md) | `PurchaseCommandTest`, `TokenSecretsTest`, `TokenConfigurationTest`, `GatewayEdgeIT.refusesAHoldWithoutAnAdmission`, `GatewayEdgeIT.refusesAnAdmissionForAnotherSale`, `GatewayEdgeIT.needsNoAdmissionToWatchASeatMap` |
| `FR-7.3` | [section 13](../../README.md#13-security), [section 12.4](../../README.md#124-holds) | - | `GatewayEdgeIT.refusesAnAdminEndpointToAnOrdinaryBuyer`, `GatewayEdgeIT.letsAnOperatorThroughToTheAdminEndpoints`, `HoldControllerTest`, `CheckoutIT.anOrderOfAnotherUserIsNotInThisUsersList` |
| `FR-7.4` | [section 6.1](../../README.md#61-gateway-servicesgateway), [section 13](../../README.md#13-security), [section 22](../../README.md#22-configuration) | - | **Nothing.** `ErrorCodeTest` covers the error code and its retryability, and nothing produces it. See [open gaps](#64-open-gaps) |
| `FR-7.5` | [section 13](../../README.md#13-security), [section 22](../../README.md#22-configuration) | [ADR-0012](../adr/0012-one-active-hold-per-user-per-event.md) | `HoldControllerTest`, `HoldIT.aUserHoldsOnlyOneSetOfSeatsPerEvent`, `HoldIT.fiveHundredConcurrentAttemptsByOneUserHoldOneSetOfSeats`, and `I8` of the checker |
| `FR-7.6` | [section 9.4](../../README.md#94-idempotency), [section 9.5](../../README.md#95-transactional-outbox-postgres-and-redis), [section 10.2](../../README.md#102-message-envelope) | [ADR-0003](../adr/0003-transactional-outbox-with-polling-relay.md), [ADR-0007](../adr/0007-json-messages-with-schema-version.md) | `IdempotentConsumerTest`, `IdempotentConsumerIT`, `MessageConsumerIT`, `PaymentIT.aRedeliveredCommandIsPerformedOnce`, `PaymentIT.aResentCommandIsPerformedOnceAndAnsweredAgain`, `PaymentIT.aCaptureWithoutAnAuthorizationEndsInTheDeadLetterTopic` |

### FR-8 Operating and verifying

| Req | Designed in | Decided by | Proved by |
|---|---|---|---|
| `FR-8.1` | [section 14](../../README.md#14-observability), [section 14.4](../../README.md#144-dashboards-and-alerts) | [ADR-0019](../adr/0019-observability-as-a-cluster-add-on.md) | `LatencyHistogramsTest`, `SaleProgressGaugesTest`, `WaitingRoomGaugesTest`, `ActuatorRequestsTest`, `scripts/check-dashboards.py` |
| `FR-8.2` | [section 17.1](../../README.md#171-the-invariant-checker) | [ADR-0021](../adr/0021-invariant-checker-reads-the-stores-directly.md) | `InvariantsTest`, `CheckerOptionsTest`, `ReportTest`, `InvariantCheckerIT` |
| `FR-8.3` | [section 17.2](../../README.md#172-the-chaos-experiments), [section 16](../../README.md#16-failure-modes) | - | `scripts/chaos.sh`, and the invariant checker it ends with |
| `FR-8.4` | [section 18.1](../../README.md#181-scenario), [section 18.1.1](../../README.md#1811-how-it-is-run), [section 18.2](../../README.md#182-environment) | - | `LoadProfileTest`, `scripts/load.sh`, `scripts/publish-run.sh`, and the published run in [`docs/benchmarks/`](../benchmarks/2026-11-09/README.md) |

---

## 6.2 Invariants to proof

The five invariants a finished run can be asked about are checked by
`load-tests/invariant-checker` after every load run. The other three are properties of an instant
or of a sequence, so they are proved by tests instead, for the reason
[README section 17.1](../../README.md#171-the-invariant-checker) gives.

| Invariant | Checked after a run | Proved by |
|---|---|---|
| `I1` seat sold at most once | Yes, from three sources: the sale rows, confirmed orders and tickets | `HoldIT.tenThousandConcurrentAttemptsOnOneHundredSeatsHoldExactlyOneHundred`, `SeatCommitIT.thePostgresGuardRefusesASecondSaleOfASeat` |
| `I2` one active hold per seat | No, nothing is left behind to find | `HoldIT.aHoldIsAllOrNothing`, `HoldIT.tenThousandConcurrentAttemptsOnOneHundredSeatsHoldExactlyOneHundred` |
| `I3` confirmed order complete | Yes, against seven conditions | `SagaIT.aCheckoutRunsToConfirmedOneReplyAtATime`, `PaymentIT.theWholeHappyPathOfOneOrder` |
| `I4` failed order clean | Yes, against seven conditions | `SagaIT.aDeclinedPaymentReleasesTheHoldAndFails`, `SagaIT.aFailedCommitVoidsAndReleasesBeforeFailing` |
| `I5` no duplicate effect | No, its evidence is a request that was repeated | `HoldIT.theSameRequestTwiceIsOneHold`, `CheckoutIT.manyConcurrentRequestsWithOneKeyProduceOneOrder`, `PaymentIT.aRedeliveredCommandIsPerformedOnce` |
| `I6` arrival order honoured | No, the queue keeps no history of it | `QueueJoinIT.numbersArrivalsAtALiveSaleInOrder`, `QueueAdmissionIT.letsInTheFrontOfTheQueueAndMovesTheHead` |
| `I7` nothing outlives its deadline | Yes, for holds and for state machines | `HoldIT.theSweeperTakesTheSeatsOfAHoldWhoseTimeRanOut`, `SagaIT.aCheckoutNobodyAnswersIsCompensatedByTheDeadline` |
| `I8` one hold per buyer per event | Yes, including dangling pointers | `HoldIT.fiveHundredConcurrentAttemptsByOneUserHoldOneSetOfSeats` |

---

## 6.3 Contracts

Two kinds of contract are generated from the code rather than written, and the build compares what
runs against what is committed. A changed endpoint or message therefore fails the build rather than
misleading the next reader.

| Contract | Committed at | Regenerated by | Compared by |
|---|---|---|---|
| HTTP API, one OpenAPI 3.1 document per service | [`docs/api/`](../api/) | `make api-docs` | `OpenApiIT` in each of the six services ([section 17.4](../../README.md#174-the-api-specifications)) |
| Messages, one JSON Schema per record | [`docs/messages/`](../messages/index.md) | `make message-docs` | `MessageSchemaTest`, `MessageCatalogTest`, `MessagePayloadsTest` ([section 17.3](../../README.md#173-the-message-schemas)) |

---

## 6.4 Open gaps

Two requirements of [chapter 3](03-fr-catalog.md) have no implementation behind them. Both are
stated as present in the README, and both were found by writing this chapter: the third column is
what makes a gap like this impossible to leave implicit.

Neither is a defect in the system as built. Both are a disagreement between the specification and
the repository, and the specification is the half that is currently wrong.

### FR-7.4, rate limiting: not implemented

| | |
|---|---|
| **Claimed in** | [section 6.1](../../README.md#61-gateway-servicesgateway) ("token-bucket rate limiting per user and per IP, backed by Redis"), [section 13](../../README.md#13-security), [section 22](../../README.md#22-configuration) (a `seatrush.ratelimit.*` row) |
| **What exists** | `RATE_LIMITED` as an error code in `libs/common`, marked retryable and covered by `ErrorCodeTest`; the client handles the code if it arrives |
| **What does not** | Any enforcement. No filter, no bucket, no `seatrush.ratelimit.*` property in the gateway configuration, and no Redis dependency in the gateway to back a bucket with |
| **Consequence** | `FR-7.5`, one hold per buyer and a maximum per hold, is the only abuse limit actually enforced. [Section 2](../../README.md#2-goals-and-non-goals) puts rate limiting in scope as one of the two halves of the abuse story, and today only one half is there |
| **To close it** | Either build it, or move it to the non-goals and future work of [section 26](../../README.md#26-roadmap). Both are decisions about the product, not about this document |

### FR-7.1, RS256 with JWKS: not implemented

| | |
|---|---|
| **Claimed in** | [section 13](../../README.md#13-security) ("JWT, HS256 locally, RS256 with a JWKS endpoint on Kubernetes"), [section 22](../../README.md#22-configuration) (the `k8s` profile "brings its own identity provider") |
| **What exists** | The HS256 path, built, configured and tested, including the separate-secret and audience arguments of `FR-7.2`. The `k8s` profile does switch the demo login off, which is implemented and tested |
| **What does not** | Any resource-server or JWKS configuration, and any `k8s` profile document in the repository. `libs/security` says in its own comments that the issuer "may one day not be us", which is the honest version |
| **Consequence** | A deployment on Kubernetes runs the `local` profile today, which [section 22](../../README.md#22-configuration) states and explains. The identity provider of a deployment facing real users has to be built, not only configured |
| **To close it** | Build the resource-server path, or state in [section 13](../../README.md#13-security) that RS256 is future work |
