package com.easytask.backend.auth;

import com.easytask.backend.config.EasyTaskProperties;
import com.easytask.backend.organization.Organization;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.UserRole;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "ZWFzeXRhc2stZGV2LW9ubHktc2VjcmV0LWtleS1jaGFuZ2UtaW4tcHJvZHVjdGlvbi0wMDE=";

    private JwtService jwtService(Duration ttl) {
        return new JwtService(new EasyTaskProperties(
                new EasyTaskProperties.Jwt(SECRET, ttl), Duration.ofDays(14), null));
    }

    private AppUser user(UUID userId, UUID orgId, UserRole role) {
        Organization organization = Organization.builder().name("Acme").build();
        organization.setId(orgId);
        AppUser user = AppUser.builder()
                .organization(organization)
                .fullName("Ava Smith")
                .email("ava@example.com")
                .passwordHash("x")
                .role(role)
                .build();
        user.setId(userId);
        return user;
    }

    @Test
    void roundTripCarriesUserOrganizationAndRole() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        JwtService service = jwtService(Duration.ofMinutes(15));

        String token = service.createAccessToken(user(userId, orgId, UserRole.MANAGER));
        AuthenticatedUser parsed = service.parseAccessToken(token);

        assertThat(parsed.id()).isEqualTo(userId);
        assertThat(parsed.organizationId()).isEqualTo(orgId);
        assertThat(parsed.role()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService service = jwtService(Duration.ofMinutes(-1));
        String token = service.createAccessToken(user(UUID.randomUUID(), UUID.randomUUID(), UserRole.EMPLOYEE));

        assertThatThrownBy(() -> service.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = jwtService(Duration.ofMinutes(15));
        String token = service.createAccessToken(user(UUID.randomUUID(), UUID.randomUUID(), UserRole.EMPLOYEE));
        String tampered = token.substring(0, token.length() - 2) + "ab";

        assertThatThrownBy(() -> service.parseAccessToken(tampered))
                .isInstanceOf(JwtException.class);
    }
}
