package io.codifica.tax_tracker.repository;

import io.codifica.tax_tracker.domain.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    long count();
}

