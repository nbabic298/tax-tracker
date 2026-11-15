# Project: Croatian Capital Gains Tax Tracker

## Purpose
A Spring Boot + PostgreSQL web app that imports crypto/stock/ETF transactions,
applies Croatian tax rules.

## Hard rules (non-negotiable, cite these when implementing)
- FIFO (First-In, First-Out) is mandatory and UNIVERSAL across all exchanges/wallets
  for the same asset. No per-exchange FIFO.
- Holding period > 2 years => gain is tax-exempt. Holding period is measured
  from original acquisition date and CARRIES OVER through crypto-to-crypto swaps.
- Crypto-to-crypto swaps are NOT taxable events. They consume source-asset FIFO lots
  and assign the consumed lots' original acquisition date + cost basis to the target asset.
- Airdrops: acquisition cost = 0. Disposal proceeds = full taxable gain (unless >2y held).
- Fees: buy fees are added to cost basis; sell fees are subtracted from proceeds.
- Netting: losses offset gains within the same calendar year only.

## Architecture
- Java 25, Spring Boot 4.x, Spring Data JPA, PostgreSQL, Flyway migrations.
- Layered: domain (pure logic, no Spring) -> service -> web.
- Domain logic (FIFO, tax calc) must be framework-free and unit-tested without Spring context.
- Money: use BigDecimal, never double. Currency: EUR internally, store original too.

## Conventions
- Every tax rule implemented must reference a source in a Javadoc comment
  (e.g., "Tax Administration opinion, 2021-09-06").
- No silent assumptions. If a rule is ambiguous, stop and ask me.
- Write tests before or alongside implementation for domain logic.
- Keep domain classes immutable where possible.

## What NOT to do
- Do NOT use any external tax libraries. All tax logic must be implemented in-house.
- Do NOT use any external crypto libraries for tax calculation. Only use them for parsing exchange data