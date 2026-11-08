# ADR-0007: JSON messages with explicit schema version; Avro deferred

- **Status**: Accepted
- **Milestone**: M2
- **README**: [§10](../../README.md#10-messaging-kafka), [§26](../../README.md#26-roadmap)

## Context

Six topics carry commands, replies and events between services ([§10.1](../../README.md#101-topics)).
Whatever they carry has to survive a deployment in which two versions of a producer and two versions
of a consumer are running at once, which is every rolling update.

The two usual answers are a binary format with a registry that enforces compatibility, or a text
format with a version field and consumers written to tolerate what they do not know.

## Decision

JSON, with an envelope that carries `messageId`, `type`, `schemaVersion`, `occurredAt`,
`correlationId`, `causationId`, `traceparent` and the payload. `type` selects the payload class and
`schemaVersion` says which shape of it this is. Consumers ignore unknown fields.

Avro and a schema registry are deferred, and named as future work in
[§26](../../README.md#26-roadmap) rather than dismissed.

## Consequences

A message on a topic can be read by a person. During an on-sale that is worth more than it sounds:
`kafka-console-consumer` on `inventory.commands` is a debugging session rather than a decoding
exercise, and the message docs of `docs/messages/` are generated from types rather than from a
registry.

There is no infrastructure to run. No registry to deploy, no compatibility mode to configure, no
subject naming strategy to agree on, and no second place where a schema can disagree with the code.

Compatibility is a convention rather than a gate, and that is the real cost. Nothing refuses to
publish a message that a consumer of the previous version cannot read: what enforces it is the rule
that fields are added and never removed or retyped, plus the JSON Schema validation of
[§17](../../README.md#17-testing-strategy) that asserts every producer emits what every consumer
accepts. A registry would catch the same mistake earlier and without anybody remembering.

Messages are larger and slower to parse than Avro. At the volumes here, which are bounded by the
admission rate rather than by the number of buyers, that has not been the constraint.

`schemaVersion` is carried and currently always 1. It is there so the first incompatible change has
somewhere to say so, which is the one thing that cannot be added retroactively.

## Alternatives considered

**Avro with a Schema Registry.** The right answer for a system whose message contracts outlive its
team, and the reason it is deferred rather than rejected: it buys enforced compatibility and smaller
payloads at the cost of a service to run, a build step to generate classes, and messages nobody can
read off a topic. Revisit when a second team consumes these topics.

**Protobuf.** Same trade as Avro without the registry's compatibility checking unless one is added
anyway.

**JSON with no version field.** Rejected because the field cannot be added later usefully: a consumer
that has to guess the shape of a message has already lost the ability to tell an old one from a new
one.
