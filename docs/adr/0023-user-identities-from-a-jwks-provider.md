# ADR-0023: User identities from a JWKS provider, with the user id derived rather than read

- **Status**: Accepted
- **Milestone**: M9
- **README**: [§12.1](../../README.md#121-auth-demo), [§13](../../README.md#13-security), [§22](../../README.md#22-configuration)

## Context

[§13](../../README.md#13-security) has said since M1 that a user JWT is "HS256 locally, RS256 with
a JWKS endpoint on Kubernetes", and [§22](../../README.md#22-configuration) that the `k8s` profile
"brings its own identity provider". Only the first half of each sentence was built. The `k8s`
profile did exactly one thing, which was to switch the demo login off, because
`DemoLoginController` carries `@Profile({"local","bench"})`. There was no resource server, no JWKS
configuration and no `k8s` profile document in the repository, and `libs/security` said so in its
own comments: the issuer "may one day not be us".

What that left was the worst of the three possible states. A cluster on the `k8s` profile has no
demo login, so nobody can obtain a token; the HS256 signer still wired and generated a secret when
none was set, so the gateway held a key it would have accepted tokens signed with; and every
request answered `401` while the pods reported themselves ready, because a probe calls the actuator
and the actuator needs no token.

Three things make this more than a configuration exercise.

**The gateway is the one process whose job is to validate identities**, so what it is allowed to
hold matters. Before this, the authentication filter depended on `UserTokenSigner`, a class that
mints.

**`UserToken.userId()` is a `UUID`** and becomes the `X-User-Id` that every service behind the
gateway keys its rows on ([§13](../../README.md#13-security)). An identity provider's `sub` is
whatever that provider chose: `auth0|65f3…`, a number, an email address.

**The admission token is a separate concern.** It is minted by queue-service for the gateway with a
secret only those two hold ([ADR-0014](0014-admission-as-a-signed-bearer-token.md)), and nothing
about an external provider touches it.

## Decision

A second implementation of reading a user token, chosen by configuration, behind an interface the
gateway depends on.

**`UserTokens` is the interface, and it only reads.** `UserTokenSigner` implements it and also
mints; `JwksUserTokens` implements it and cannot. The gateway's authentication filter now depends
on the interface. A deployment that trusts a provider therefore has no signing key in the process
at all, and that is the point rather than a side effect: a validator holding a signing key is a
validator that can forge what it validates. Minting is deliberately absent from the interface,
because there is no honest `issue` that both implementations could provide.

**Which one wires is decided by `seatrush.auth.jwks.uri`, not by the profile.** Blank counts as
absent, because a Helm chart renders every value of its env map and a release on the `local`
profile therefore passes an empty string.

**The issuer and the audience have no defaults and are demanded when a URI is set.** The audience
is the one a signature check cannot supply: a provider signs tokens for every application it serves
out of one key set, so without it a token minted for a different one of them is a valid SeatRush
identity.

**The algorithm is pinned to RS256 and never read out of the token.** Same argument as `JwtCodec`
makes for HS256. With a JWKS in play the sharpest version of alg confusion is an HS256 token signed
with the published public key as its secret.

**The user id is derived from the issuer and the subject**, hashed into a UUID the way the demo
login hashes a username, rather than parsed out of `sub`.

**Expiry is checked against the injected clock with no skew tolerance**, which meant writing the
claims verifier rather than configuring Nimbus's: `DefaultJWTClaimsVerifier` reads the system clock
and allows a minute of leeway.

**The `k8s` profile enforces that a provider exists.** `IdentityProviderRequirement` fails the
startup with the three variables to set. A process that exits with a sentence beats a fleet of pods
that are ready and useless.

## Consequences

A deployment facing real users is now a configuration rather than a feature request: three values,
and the gateway reads identities it cannot issue.

The `local` profile is untouched, which keeps `make up`, `make k8s-up`, the demo login, the seed and
every benchmark working exactly as before. That is also what the empty-value handling buys: a
release that never asked for a provider behaves as if this ADR did not exist.

**Deriving the user id is the consequence worth understanding.** The same person at the same
provider is always the same buyer, which is the property the whole system needs: a buyer who
reloads must not lose the hold, the order and the tickets they just paid for. What it costs is that
the id in SeatRush is not the id in the provider, so correlating the two means hashing the subject
the same way. Two providers cannot collide, because the issuer is part of what is hashed, and
changing the issuer of an existing deployment changes every user id: that is a migration, not a
configuration change.

**A provider that cannot be reached refuses every request.** A gateway that accepted an identity
because it could not check the signature would be no gateway at all. This is the opposite of the
rate limiter's fail-open of [ADR-0022](0022-token-buckets-in-redis-at-the-edge.md), and the two are
different for a reason worth stating: a limit protects the system from a caller, so losing it
briefly costs little, while a signature check is the only thing standing between a caller and
somebody else's order.

A forged token naming a key the provider does not publish is logged at debug rather than warn, after
the key-set refresh is rate limited. Without that split, a forgery attack would be a log flood whose
size the attacker chooses.

**`DemoLogins` had to be scoped to the profiles that have a login**, which is a small bug this
change surfaced: the class was a bean in every profile, so the `k8s` profile had a login service
asking for a signing key no deployment should hold. Before the RS256 path existed there was always
a signer to inject, so nothing noticed.

**Role mapping is now a provider's business.** Roles are read from a configurable claim, as a list
or as a space-delimited string, and an unreadable roles claim is a refusal rather than an empty set,
because the one question anybody asks of them is whether the caller is an administrator.

## Alternatives considered

**Spring Security's resource server.** The obvious answer, and `spring-security-oauth2-jose` would
have supplied the JWKS caching and the validators. Rejected because `libs/security` depends on
Nimbus alone and already owns the token vocabulary of this system: `JwtCodec`, `TokenAudience`, the
two-secret argument of ADR-0014. Adding the resource server would have put two JOSE configurations
in one process, each with its own idea of clock skew and algorithm policy, and the thing it saves
is about sixty lines of configuration over the library Spring Security uses underneath anyway.

**Requiring the provider's `sub` to be a UUID.** Simplest, and documented as a constraint on the
identity provider. Rejected because it is a constraint almost no provider satisfies, so it would
have made the feature unusable with every provider a deployment is likely to have.

**A user table mapping provider subjects to internal ids.** The database answer, and the right one
for a system that needs profiles, email addresses or an audit trail of who logged in when. Rejected
because this system has no user table by decision ([§2](../../README.md#2-goals-and-non-goals)), and
adding one for an id that can be derived would put a database read on the hottest path in the
system.

**Deciding by profile rather than by configuration.** It would have matched the README sentence
most literally. Rejected because a library that branches on profile names is a library that cannot
be tested without them, and because the configuration is the honest condition: what makes a
deployment trust a provider is having one, not being called `k8s`.

**Letting the request through when the JWKS endpoint is unreachable**, by analogy with the rate
limiter of ADR-0022. Rejected for the reason in the consequences: the two are not analogous.

**Validating `iat` as `JwtCodec` does.** Rejected because an external provider is not obliged to
write one, and refusing an identity for that would be refusing a buyer over how somebody else's
provider is configured.
