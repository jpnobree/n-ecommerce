package com.atelier.identity.api.dto;

import com.atelier.identity.service.SessionService;

/** O refresh token nunca vai no corpo: só no cookie HttpOnly. */
public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {

    public static AuthResponse of(SessionService.Session s) {
        return new AuthResponse(s.accessToken(), s.expiresInSeconds(), UserResponse.of(s.user()));
    }
}
