package com.easytask.backend.auth;

import com.easytask.backend.config.EasyTaskProperties;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.role.Role;
import com.easytask.backend.user.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtService {

    private static final String CLAIM_ORGANIZATION = "org";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_SCOPE = "scope";
    private static final String CLAIM_PERMISSIONS = "permissions";

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(EasyTaskProperties properties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
        this.accessTokenTtl = properties.jwt().accessTokenTtl();
    }

    /**
     * Claims carry the fully-expanded authorization data (decision D12), so
     * requests never need a DB round-trip for permissions. Staleness is
     * bounded by the access TTL; role changes also revoke refresh tokens.
     */
    public String createAccessToken(AppUser user) {
        Role role = user.getRole();
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_ORGANIZATION, user.getOrganization().getId().toString())
                .claim(CLAIM_ROLE, role.getName())
                .claim(CLAIM_SCOPE, role.getDataScope().name())
                .claim(CLAIM_PERMISSIONS, List.copyOf(role.getPermissions()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * @throws io.jsonwebtoken.JwtException if the token is invalid, tampered with, or expired
     */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        List<?> rawPermissions = claims.get(CLAIM_PERMISSIONS, List.class);
        Set<String> permissions = rawPermissions == null
                ? Set.of()
                : rawPermissions.stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_ORGANIZATION, String.class)),
                claims.get(CLAIM_ROLE, String.class),
                DataScope.valueOf(claims.get(CLAIM_SCOPE, String.class)),
                permissions);
    }
}
