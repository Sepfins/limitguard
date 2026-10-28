# LimitGuard Platform

LimitGuard is a Kotlin library for building a limit service in a banking-style system. A
backend, for example, Spring Boot, depends on `limitguard-core` and becomes that service: it
keeps its own REST or messaging APIs, security, and scheduling, and calls the library to keep
the limit catalog, decide whether a payment fits a customer's limits, and change those limits
through an auditable workflow. The library owns its PostgreSQL tables and migrations and has
no controllers, message consumers, scheduled jobs, or application entrypoint.

The same facade can also be embedded in a backend that is not a limit service, such as a
payment service. That backend then hosts the LimitGuard tables, mirrors its customers and
accounts into the catalog, and runs the due-change and expiry passes itself. The demo does
this so that everything runs in one process; a bank would more likely keep one, or several,
limit services and let its payment services call them.

`services/` holds the library (`limitguard-core`), a customer banking demo that consumes it
(`demo-service`), and a tenant configuration console built with Spring Boot and Angular
(`limitguard-admin`).

## The limit model

A tenant (bank) registers its channels, operation types, funding source products, funding
sources, and party profiles. An owner can give another party profile a mandate over some or
all of the owner's funding sources.

A limit configuration is the tenant's template for one kind of limit. It fixes the kind (per
transaction, or periodic with a daily or monthly period), the currency, the funding sources,
channels, and operation types it covers, its priority, whether its usage cascades to later
limits or is isolated, and the largest amount a customer may be given, which may be
unlimited. Configurations nest, so instant transfers can sit under all transfers.

A limit rule is one owner's amount for one configuration, either for all of the owner's
mandates or for one mandate. The amount is a value in the configuration's currency or
explicitly unlimited, and never exceeds the configuration's maximum. An owner has at most
one rule per configuration and mandate.

Authorization finds the configurations that cover a payment's funding source, channel, and
operation, loads the owner's active rules for them, and checks them in priority order. The
library does not convert currencies: the host passes the amount in the currency of the rules
that apply, and a bounded rule in any other currency denies the payment. Periodic usage is
counted per acting party profile in daily or monthly buckets: an authorization reserves, a
capture consumes, and a release or reversal restores. When no rule matches, the tenant
chooses to allow, deny, or leave the decision to the host.

Rules change only through change requests that set an amount, deactivate, reactivate, or
delete a rule. Tenant policies can allow, deny, or require approval, and an approver
approves or rejects the request as submitted. A request can wait for a later application
time, and it fails if its deadline passes first.

## Adopting the library

The host builds one `Limitguard` facade from a `DataSource` in its composition root and runs
the packaged Flyway migrations from `classpath:db/limitguard/migration`:

```kotlin
@Bean
fun limitguard(dataSource: DataSource): Limitguard = JooqLimitguardFactory(dataSource).create()
```

The facade groups the catalog, catalog query, change, evaluation, and inquiry services. The
host maps its own request DTOs to the library's commands and its results back out:

```kotlin
val result = limitguard.limitEvaluationService.authorizeTransaction(
    AuthorizeTransactionCommand(
        tenantId = tenantId,
        actingPartyProfileId = customerId,
        fundingSourceId = accountId,
        operationTypeId = cardPurchaseId,
        channelId = mobileChannelId,
        amount = BigDecimal("85.40"),
        currency = "EUR",
        idempotencyKey = paymentId,
    ),
)
```

Limit changes go through `limitChangeService.requestLimitRuleChange` in the same way.
Authentication and permissions stay in the host, which also triggers
`applyDueLimitChanges` and `expireDueAuthorizations` on its own schedule. A command retried
with the same idempotency key is not applied twice.

Inside the library, `api` holds the public services, commands, results, and errors;
`application` orchestrates use cases; `domain` holds the model and its invariants; `port`
declares persistence, policy, clock, and ID boundaries; and `infrastructure` holds the jOOQ
and JSON Logic adapters. Hosts depend only on `api`, except for the one factory call above
and any port they implement themselves. LimitGuard Admin is a limit service built this way,
and the demo service is a bank backend that embeds the same facade next to its own accounts
and payments.

## What a host can plug in

Core mints its own UUIDs through the `IdGenerator` port, and every command names tenants,
parties, accounts, and configurations by them. A bank keys its customers by customer number
and its accounts by IBAN. Hence, a tenant registers an identifier scheme per entity type, party
profiles and funding sources carry those identifiers next to the library's ids, and the host
resolves one back with `resolveEntityIdentifier` before it calls a command. Channels,
operation types, products, configurations, and policies carry a tenant-chosen code instead.

A limit-change policy names the engine that evaluates its condition and actions. Core ships
JSON Logic; a host passes its own `PolicyEngine` implementations to the factory, one per
language. Host facts reach a policy as `policyContext` on the request, which is how the demo
sends the customer's current exposure.

The clock is the `ClockProvider` port, read once per command, so a test fixes time by
passing its own. A host that builds the facade with `relayLimitChangeEvents = true` gets
every limit-change event queued in an outbox and hands batches to a sink of its choosing,
such as a Kafka producer, at least once and in order. The library opens its own database
transaction per command, so a host's Spring transaction does not enclose a library call.
Queries render no schema name, so that the tables can live in a schema of the host's choosing.
There is no Spring autoconfiguration and no cache.

## What is not there yet

- Commands take only the library's ids: no command accepts a customer number or IBAN in
  their place.
- Only JSON Logic has an engine. CEL, SQL functions, and an external decision service are
  named in the enum and the schema and nothing more.
- Policies run at submission only and decide allow, deny, require approval, or pass on,
  optionally postponing the effective time or capping the amount. Other phases and
  decisions in the enums are refused, and a policy cannot be amended or retired.
- Periods are daily and monthly. There is no weekly period, count limit, time-of-day
  limit, or cap shared between an owner and their mandate holders.

## Running the demo

The demo is a fictional household banking journey on port `8081`. Customer Banking shows
accounts, activity, and limit settings; Payment Lab drives authorization, capture, release,
and reversal and answers held limit changes as the account holder. Its compose file deploys
it with Keycloak and LimitGuard Admin over the same database. On a clean checkout, generate
the jOOQ bindings first (see below), then:

```bash
cd services/demo-service
./gradlew bootJar
../limitguard-admin/gradlew -p ../limitguard-admin bootJar
docker compose up -d --build --wait
```

Open [Customer Banking](http://localhost:8081/limitguard/demo) and
[Payment Lab](http://localhost:8081/limitguard/payments) in separate tabs, and
[LimitGuard Admin](http://localhost:8080/limitguard/admin) as `platform-admin` with the
password `limitguard`. The [demo README](services/demo-service/README.md) describes the
scenario and a curl journey.

## Running the tests

Each service has its own Gradle wrapper: run `./gradlew test` in `services/limitguard-core`,
`services/demo-service`, and `services/limitguard-admin`. The core and demo tests start
PostgreSQL with Testcontainers, so Docker must be running. The core tests drive the public
facade against a real database, including one journey from onboarding through rule
approval, authorization, capture, reversal, and an amount change.

## Generating jOOQ bindings

Generated jOOQ sources are never committed, so a clean checkout, or any build after
`./gradlew clean`, must generate them against a migrated database before compiling. This
uses a throwaway PostgreSQL on port 55432 so it does not touch the normal local database:

```bash
cd "$(git rev-parse --show-toplevel)"
export POSTGRES_PORT=55432 POSTGRES_DB=limitguard_jooq
export POSTGRES_USER=limitguard_jooq POSTGRES_PASSWORD=limitguard_jooq
docker compose -p limitguard-jooq up -d --wait postgres
docker compose -p limitguard-jooq run --rm flyway

cd services/limitguard-core
JOOQ_JDBC_URL="jdbc:postgresql://localhost:55432/limitguard_jooq" \
JOOQ_JDBC_USER=limitguard_jooq JOOQ_JDBC_PASSWORD=limitguard_jooq \
JOOQ_INPUT_SCHEMA=public \
./gradlew clean generateJooq classes test --console=plain

cd ../..
docker compose -p limitguard-jooq down -v --remove-orphans
unset POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
```
