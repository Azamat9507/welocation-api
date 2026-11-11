package com.minsu.welocation.auth;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

// Only the id is taken from the token. Roles are read from the database where they decide something,
// so a demoted or deleted admin loses rights at once, not when the token expires.
public record CurrentUser(long id) {

    public static CurrentUser from(JwtAuthenticationToken token) {
        return new CurrentUser(Long.parseLong(token.getToken().getSubject()));
    }
}
