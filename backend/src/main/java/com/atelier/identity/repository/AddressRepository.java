package com.atelier.identity.repository;

import com.atelier.identity.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtDesc(Long userId);

    Optional<Address> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Address> findFirstByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndDeletedAtIsNull(Long userId);

    // Dois comandos (limpa, depois marca) para nunca violar o índice único parcial de endereço principal.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Address a set a.isDefault = false where a.userId = :userId and a.isDefault = true")
    void clearDefault(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Address a set a.isDefault = true where a.id = :id and a.userId = :userId and a.deletedAt is null")
    int markDefault(Long id, Long userId);
}
