package com.atelier.identity.api;

import com.atelier.identity.api.dto.*;
import com.atelier.identity.service.AccountService;
import com.atelier.identity.service.AddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Recursos do próprio usuário. O id vem sempre do token, nunca da URL. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccountService accounts;
    private final AddressService addresses;
    private final RefreshCookies cookies;

    MeController(AccountService accounts, AddressService addresses, RefreshCookies cookies) {
        this.accounts = accounts;
        this.addresses = addresses;
        this.cookies = cookies;
    }

    @GetMapping
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(accounts.get(userId(jwt)));
    }

    @PutMapping
    UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
        return UserResponse.of(accounts.update(userId(jwt), req));
    }

    /** Devolve uma sessão nova: as anteriores (inclusive a atual) foram revogadas. */
    @PutMapping("/password")
    ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req,
                                                HttpServletRequest http) {
        var session = accounts.changePassword(userId(jwt), req.currentPassword(), req.newPassword(), AuthController.client(http));
        return cookies.respond(HttpStatus.OK, session);
    }

    @GetMapping("/addresses")
    List<AddressResponse> addresses(@AuthenticationPrincipal Jwt jwt) {
        return addresses.list(userId(jwt)).stream().map(AddressResponse::of).toList();
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    AddressResponse createAddress(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddressRequest req) {
        return AddressResponse.of(addresses.create(userId(jwt), req));
    }

    @PutMapping("/addresses/{id}")
    AddressResponse updateAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody AddressRequest req) {
        return AddressResponse.of(addresses.update(userId(jwt), id, req));
    }

    @DeleteMapping("/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        addresses.delete(userId(jwt), id);
    }

    @PutMapping("/addresses/{id}/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        addresses.setDefault(userId(jwt), id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
