package com.atelier.identity.repository;

import com.atelier.identity.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    Optional<UserToken> findByTokenHashAndType(String tokenHash, UserToken.Type type);

    /** Só o link mais recente vale: tokens anteriores do mesmo tipo são consumidos. */
    @Modifying
    @Query("update UserToken t set t.usedAt = :now where t.userId = :userId and t.type = :type and t.usedAt is null")
    void consumeAll(Long userId, UserToken.Type type, Instant now);
}
