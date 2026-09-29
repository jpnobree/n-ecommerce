package com.atelier.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Sessão de refresh. Só o hash SHA-256 do token é persistido; a família agrupa as rotações. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    public UUID id;
    public Long userId;
    public String tokenHash;
    public UUID familyId;
    public Instant expiresAt;
    public Instant revokedAt;
    public UUID replacedBy;
    public String userAgent;
    public String ip;
    public Instant createdAt;
}
