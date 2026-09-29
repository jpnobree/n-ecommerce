package com.atelier.catalog.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "color")
public class Color {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String slug;
    public String hex;
}
