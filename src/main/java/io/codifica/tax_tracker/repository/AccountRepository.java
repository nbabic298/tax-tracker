package io.codifica.tax_tracker.repository;

import io.codifica.tax_tracker.domain.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByNameAndType(String name, String type);
}

