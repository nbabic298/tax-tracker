-- Flyway Migration: V1__init.sql
-- Initial schema for tax-tracker
-- Covers all domain entities: Asset, Account, Transaction, TaxLot, Disposal, SwapLink

-- Asset table
CREATE TABLE asset (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(50) NOT NULL, -- CRYPTO, STOCK, ETF, TOKENIZED_STOCK
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (symbol)
);

CREATE INDEX idx_asset_symbol ON asset(symbol);

-- Account table
CREATE TABLE account (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL, -- e.g., exchange, broker, wallet
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_account_name ON account(name);

-- Transaction table
CREATE TABLE transaction (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    type VARCHAR(50) NOT NULL, -- BUY, SELL, SWAP_IN, SWAP_OUT, AIRDROP, DIVIDEND, FEE, TRANSFER_IN, TRANSFER_OUT
    amount NUMERIC(30, 18) NOT NULL,
    unit_price_eur NUMERIC(20, 8),
    fee_eur NUMERIC(20, 8),
    external_id VARCHAR(100),
    source_file VARCHAR(255),
    raw_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
    UNIQUE (account_id, external_id)
);

CREATE INDEX idx_transaction_account_id ON transaction(account_id);
CREATE INDEX idx_transaction_asset_id ON transaction(asset_id);
CREATE INDEX idx_transaction_timestamp ON transaction(timestamp);
CREATE INDEX idx_transaction_type ON transaction(type);
CREATE INDEX idx_transaction_external_id ON transaction(external_id);

-- TaxLot table
CREATE TABLE tax_lot (
    id BIGSERIAL PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    acquired_at TIMESTAMP NOT NULL,
    original_amount NUMERIC(30, 18) NOT NULL,
    remaining_amount NUMERIC(30, 18) NOT NULL,
    cost_basis_eur NUMERIC(20, 8) NOT NULL,
    source_transaction_id BIGINT NOT NULL,
    carried_over_from_lot_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_tax_lot_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tax_lot_source_transaction FOREIGN KEY (source_transaction_id) REFERENCES transaction(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tax_lot_carried_over_from FOREIGN KEY (carried_over_from_lot_id) REFERENCES tax_lot(id) ON DELETE SET NULL
);

-- Indexes for FIFO queries: (asset_id, acquired_at) for FIFO lot selection
CREATE INDEX idx_tax_lot_asset_acquired ON tax_lot(asset_id, acquired_at);
CREATE INDEX idx_tax_lot_asset ON tax_lot(asset_id);

-- Disposal table
CREATE TABLE disposal (
    id BIGSERIAL PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    disposed_at TIMESTAMP NOT NULL,
    amount NUMERIC(30, 18) NOT NULL,
    proceeds_eur NUMERIC(20, 8) NOT NULL,
    cost_basis_eur NUMERIC(20, 8) NOT NULL,
    gain_eur NUMERIC(20, 8) NOT NULL,
    is_exempt BOOLEAN NOT NULL,
    tax_year INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_disposal_lot FOREIGN KEY (lot_id) REFERENCES tax_lot(id) ON DELETE RESTRICT
);

-- Indexes for disposal queries: by tax year (annual netting) and lot
CREATE INDEX idx_disposal_tax_year ON disposal(tax_year);
CREATE INDEX idx_disposal_lot_id ON disposal(lot_id);

-- SwapLink table
CREATE TABLE swap_link (
    id BIGSERIAL PRIMARY KEY,
    source_transaction_id BIGINT NOT NULL,
    target_transaction_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_swap_link_source FOREIGN KEY (source_transaction_id) REFERENCES transaction(id) ON DELETE RESTRICT,
    CONSTRAINT fk_swap_link_target FOREIGN KEY (target_transaction_id) REFERENCES transaction(id) ON DELETE RESTRICT,
    UNIQUE (source_transaction_id, target_transaction_id)
);

CREATE INDEX idx_swap_link_source ON swap_link(source_transaction_id);
CREATE INDEX idx_swap_link_target ON swap_link(target_transaction_id);

