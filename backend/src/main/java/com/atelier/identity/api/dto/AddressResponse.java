package com.atelier.identity.api.dto;

import com.atelier.identity.domain.Address;

public record AddressResponse(Long id, String label, String recipientName, String phone, String postalCode, String state,
                              String city, String district, String street, String number, String complement,
                              String reference, boolean isDefault) {

    public static AddressResponse of(Address a) {
        return new AddressResponse(a.id, a.label, a.recipientName, a.phone, a.postalCode, a.state, a.city, a.district,
                a.street, a.number, a.complement, a.reference, a.isDefault);
    }
}
