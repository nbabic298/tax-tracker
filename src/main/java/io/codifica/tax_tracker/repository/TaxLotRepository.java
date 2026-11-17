package io.codifica.tax_tracker.repository;

import io.codifica.tax_tracker.domain.entity.Asset;
import io.codifica.tax_tracker.domain.entity.TaxLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaxLotRepository extends JpaRepository<TaxLot, Long> {

    /**
     * Finds all OPEN tax lots for an asset with remaining amount greater than zero.
     * Open lots are those where closedAt is null.
     * Used for FIFO disposal and fee deduction.
     */
    @Query("SELECT t FROM TaxLot t WHERE t.asset = :asset " +
           "AND t.remainingAmount > :minAmount AND t.closedAt IS NULL " +
           "ORDER BY t.acquiredAt ASC")
    List<TaxLot> findOpenByAssetOrderByAcquiredAtAsc(@Param("asset") Asset asset,
                                                      @Param("minAmount") BigDecimal minAmount);

    /**
     * Finds all OPEN tax lots for an asset, ordered by acquisition date (FIFO).
     */
    @Query("SELECT t FROM TaxLot t WHERE t.asset = :asset AND t.closedAt IS NULL " +
           "ORDER BY t.acquiredAt ASC")
    List<TaxLot> findOpenByAssetOrderByAcquiredAtAsc(@Param("asset") Asset asset);

    /**
     * Finds the oldest OPEN tax lot for an asset with remaining amount.
     */
    @Query("SELECT t FROM TaxLot t WHERE t.asset = :asset " +
           "AND t.remainingAmount > :minAmount AND t.closedAt IS NULL " +
           "ORDER BY t.acquiredAt ASC LIMIT 1")
    Optional<TaxLot> findFirstOpenByAssetOrderByAcquiredAtAsc(@Param("asset") Asset asset,
                                                               @Param("minAmount") BigDecimal minAmount);

    /**
     * Finds all tax lots for an asset (including closed ones), for audit purposes.
     */
    List<TaxLot> findByAssetOrderByAcquiredAtAsc(Asset asset);

    /**
     * Finds all tax lots for an asset with remaining amount greater than zero (including closed).
     */
    List<TaxLot> findByAssetAndRemainingAmountGreaterThan(Asset asset, BigDecimal minAmount);
}


