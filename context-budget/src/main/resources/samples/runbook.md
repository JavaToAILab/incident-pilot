# Runbook: payment-service database connection pool exhaustion

## Symptoms

Payment requests slow down sharply, then begin to fail. The service logs warnings that no
connection is available from the pool within the configured timeout, followed by errors
when ledger entries cannot be persisted. Upstream callers see rising latency and an
increase in server errors. The health endpoint usually stays green, because the process
itself is still running.

## Likely causes

The most common cause is a slow downstream dependency that holds database connections
open for longer than usual, for example the ledger service responding slowly while a
transaction is still open. A recent deploy that changed transaction boundaries or added
a query inside a transaction is the second most common cause. Less often, the database
itself is under load from a batch job or a long-running migration.

## First steps

Check whether a deploy went out in the last hour and compare its timing with the first
warning. Look at active and waiting connection counts on the pool dashboard. Check the
ledger service's latency for the same window. If a deploy lines up with the first
warning and the downstream services are healthy, roll back the deploy and watch the
waiting count fall. Do not raise the pool size as a first response: it usually moves the
bottleneck to the database and makes the recovery slower.

## Escalation

If rolling back does not reduce waiting connections within ten minutes, page the
database on-call engineer and the owner of the ledger service.
