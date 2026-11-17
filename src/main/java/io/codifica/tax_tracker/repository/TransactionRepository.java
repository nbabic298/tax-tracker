package io.codifica.tax_tracker.repository;

import io.codifica.tax_tracker.domain.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    long count();

    /**
     * Fetches all transactions with eager loading of Asset and Account.
     * Uses JOIN FETCH to load associations in a single query.
     */
    @Query("SELECT DISTINCT t FROM Transaction t " +
           "JOIN FETCH t.asset a " +
           "JOIN FETCH t.account ac " +
           "ORDER BY t.timestamp ASC")
    List<Transaction> findAllWithAssetAndAccountEager();
}

