package com.atelier.catalog.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "size")
public class Size {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String slug;
    public String sizeGroup;
    public int sortOrder;
}
