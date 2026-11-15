# Tax Tracker - Domain Model & Database Schema Summary

## Deliverables Completed

### 1. JPA Entities (with BigDecimal for money, proper relationships)

**Entities created:**

- **Asset.java** - Asset reference data (symbol, name, assetType enum)
- **Account.java** - Exchange/broker accounts
- **Transaction.java** - All taxable events with JSONB support for raw API responses
- **TaxLot.java** - FIFO lot tracking with self-referencing carry-over chain
- **Disposal.java** - Discrete disposal events with gain/loss and tax year tracking
- **SwapLink.java** - Links SWAP_OUT and SWAP_IN transaction pairs

**Enums:**
- **AssetType** - CRYPTO, STOCK, ETF, TOKENIZED_STOCK
- **TransactionType** - BUY, SELL, SWAP_IN, SWAP_OUT, AIRDROP, DIVIDEND, FEE, TRANSFER_IN, TRANSFER_OUT

### 2. Liquibase Database Schema

**Location:** `src/main/resources/db/changelog/`
- **db.changelog-master.yaml** - Master changelog entry point
- **001-initial-schema.yaml** - Full initial schema with all tables, indexes, and foreign keys

**Tables created:**
- `asset` - Ticker/name reference with unique symbol constraint
- `account` - Exchange/broker accounts
- `transaction` - All events with JSONB raw_json column and unique (account_id, external_id)
- `tax_lot` - FIFO lots with composite index (asset_id, acquired_at) for efficient FIFO queries
- `disposal` - Partial/full disposals with gain calculations and tax year index
- `swap_link` - Swap leg pairing with unique constraint

**Key Indexes:**
- `(asset_id, acquired_at)` on tax_lot - supports FIFO lot selection
- `(tax_year)` on disposal - supports annual netting and tax reporting
- Foreign keys enforce referential integrity with RESTRICT on delete (no orphan data)

### 3. Configuration

**application.yaml** - Updated with:
- PostgreSQL connection (localhost:3305, user: tax_tracker, db: tax_tracker)
- JPA/Hibernate configuration with validation DDL mode
- Liquibase changelog location and enabled flag

**pom.xml** - Updated with:
- `spring-boot-starter-liquibase` for schema versioning
- `spring-boot-starter-data-jpa` for ORM
- PostgreSQL driver
- Lombok for entity boilerplate
- Java 21 LTS

---

## Modeling Decisions

1. **BigDecimal Precision** - All money fields use NUMERIC(20,8) for EUR amounts, supporting up to 12 digits with 8 decimal places. Quantities use NUMERIC(30,18) for high-precision assets.

2. **TaxLot.carriedOverFromLotId (self-reference)** - Enables audit trail of acquisition date chains through swaps. The original acquisition date is preserved for the 2-year holding period exemption calculation.

3. **Disposal as separate entity** - Allows partial disposals from the same lot, with discrete gain/loss records per tax year. Supports both netting (within tax year) and exemption (holding > 2 years).

4. **SwapLink pairing** - Explicit link between SWAP_OUT and SWAP_IN prevents dangling transactions and enables bi-directional traversal.

5. **Transaction.rawJson (JSONB)** - Stores original exchange API response for audit, re-import, and debugging without requiring external data sources.

6. **External ID Uniqueness** - Unique per (account_id, external_id) prevents duplicates across imports while allowing the same exchange ID on different accounts.

7. **Indexes** - Composite index `(asset_id, acquired_at)` on tax_lot supports fast FIFO lot retrieval; tax_year index on disposal supports annual tax calculations.

8. **Audit Trail** - All entities have `created_at` timestamp (set at record creation via @PrePersist). Business dates (acquired_at, disposed_at, timestamp) are distinct from audit timestamps.

---

## Notes

- **No FIFO or tax logic implemented** - This is schema only. Tax calculation logic will be implemented in service/calculation layer.
- **Liquibase changesets** - Each logical change (table creation, index, foreign key) is a separate changeset for fine-grained version control and rollback capability.
- **JSONB support** - PostgreSQL-specific; stores original API responses as structured JSON for maximum flexibility.
- **Holding period exemption** - Implemented via `carriedOverFromLotId` traversal to find original acquisition date for 2-year threshold.

