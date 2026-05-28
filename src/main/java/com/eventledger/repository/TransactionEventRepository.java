package com.eventledger.repository;

import com.eventledger.model.TransactionEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface TransactionEventRepository extends JpaRepository<TransactionEvent, String> {

    /**
     * Full list, always ordered chronologically — handles out-of-order arrival.
     */
    List<TransactionEvent> findByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Paginated list, always ordered chronologically.
     */
    Page<TransactionEvent> findByAccountIdOrderByEventTimestampAsc(String accountId, Pageable pageable);

    /**
     * Net balance: sum(CREDIT) - sum(DEBIT).
     * Uses JPQL CASE expression so the result is always correct regardless of
     * insertion order. COALESCE guards against a null result on an empty set.
     */
    @Query("""
            SELECT COALESCE(
                SUM(CASE WHEN e.type = com.eventledger.enums.EventType.CREDIT
                         THEN e.amount
                         ELSE -e.amount END),
                0)
            FROM TransactionEvent e
            WHERE e.accountId = :accountId
            """)
    BigDecimal computeBalance(@Param("accountId") String accountId);

    boolean existsByAccountId(String accountId);
}
