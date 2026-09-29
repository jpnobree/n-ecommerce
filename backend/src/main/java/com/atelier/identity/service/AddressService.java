package com.atelier.identity.service;

import com.atelier.identity.api.dto.AddressRequest;
import com.atelier.identity.domain.Address;
import com.atelier.identity.repository.AddressRepository;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Endereços do usuário: até 10, exatamente um principal quando houver algum.
 * Endereço de outro usuário responde 404 (não confirma existência).
 */
@Service
public class AddressService {

    static final int MAX_ADDRESSES = 10;

    private final AddressRepository addresses;
    private final Clock clock;

    AddressService(AddressRepository addresses, Clock clock) {
        this.addresses = addresses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Address> list(Long userId) {
        return addresses.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtDesc(userId);
    }

    @Transactional
    public Address create(Long userId, AddressRequest req) {
        long count = addresses.countByUserIdAndDeletedAtIsNull(userId);
        if (count >= MAX_ADDRESSES) throw new BusinessException(ErrorCode.ADDRESS_LIMIT_REACHED);
        var address = new Address();
        address.userId = userId;
        address.isDefault = count == 0;
        apply(address, req);
        return addresses.save(address);
    }

    @Transactional
    public Address update(Long userId, Long id, AddressRequest req) {
        Address address = owned(userId, id);
        apply(address, req);
        return address;
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Address address = owned(userId, id);
        address.deletedAt = clock.instant();
        boolean wasDefault = address.isDefault;
        address.isDefault = false;
        addresses.flush();
        if (wasDefault) {
            addresses.findFirstByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)
                    .ifPresent(next -> addresses.markDefault(next.id, userId));
        }
    }

    @Transactional
    public void setDefault(Long userId, Long id) {
        owned(userId, id);
        addresses.clearDefault(userId);
        addresses.markDefault(id, userId);
    }

    private Address owned(Long userId, Long id) {
        return addresses.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static void apply(Address a, AddressRequest req) {
        a.label = req.label();
        a.recipientName = req.recipientName().trim();
        a.phone = req.phone();
        a.postalCode = req.postalCode();
        a.state = req.state();
        a.city = req.city().trim();
        a.district = req.district().trim();
        a.street = req.street().trim();
        a.number = req.number().trim();
        a.complement = req.complement();
        a.reference = req.reference();
    }
}
