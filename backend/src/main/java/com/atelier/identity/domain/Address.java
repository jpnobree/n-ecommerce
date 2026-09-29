package com.atelier.identity.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "address")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String label;
    public String recipientName;
    public String phone;
    public String postalCode;
    public String state;
    public String city;
    public String district;
    public String street;
    public String number;
    public String complement;
    public String reference;
    public boolean isDefault;
    public Instant deletedAt;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;
}
