package com.atelier.content;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Conteúdo agendável da home: HERO (carrossel principal), CAMPAIGN (blocos editoriais), STRIP (faixa de aviso). */
@Entity
@Table(name = "banner")
public class Banner {

    public enum Position { HERO, CAMPAIGN, STRIP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    public Position position;

    public String title;
    public String subtitle;
    public String ctaLabel;
    public String linkUrl;
    public String imageDesktopUrl;
    public String imageMobileUrl;
    public Instant startsAt;
    public Instant endsAt;
    public boolean active = true;
    public int sortOrder;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;
}
