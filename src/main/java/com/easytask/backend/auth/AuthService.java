package com.easytask.backend.auth;

import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.UnauthenticatedException;
import com.easytask.backend.organization.Organization;
import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import com.easytask.backend.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterOrganizationResponse registerOrganization(RegisterOrganizationRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("Email is already used");
        }
        Organization organization = organizationRepository.save(Organization.builder()
                .name(request.organizationName())
                .build());
        AppUser admin = userRepository.save(AppUser.builder()
                .organization(organization)
                .fullName(request.adminFullName())
                .email(request.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.ORGANIZATION_ADMIN)
                .build());
        return new RegisterOrganizationResponse(
                new RegisterOrganizationResponse.OrganizationPayload(organization.getId(), organization.getName()),
                new RegisterOrganizationResponse.UserPayload(
                        admin.getId(), admin.getFullName(), admin.getEmail(), admin.getRole()),
                jwtService.createAccessToken(admin),
                refreshTokenService.issue(admin));
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        AppUser user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new UnauthenticatedException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthenticatedException("Invalid email or password");
        }
        if (!user.isActive()) {
            throw new ForbiddenException("Account is deactivated");
        }
        return new LoginResponse(
                new LoginResponse.UserPayload(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                        user.getOrganization().getName()),
                jwtService.createAccessToken(user),
                refreshTokenService.issue(user));
    }

    @Transactional
    public TokenPairResponse refresh(RefreshRequest request) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(request.refreshToken());
        return new TokenPairResponse(jwtService.createAccessToken(rotated.user()), rotated.refreshToken());
    }

    @Transactional
    public void logout(UUID userId, LogoutRequest request) {
        refreshTokenService.revoke(request.refreshToken(), userId);
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthenticatedException("User no longer exists"));
        return new MeResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                user.getOrganization().getName());
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthenticatedException("User no longer exists"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ForbiddenException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // changing the password invalidates every session; access tokens expire naturally
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }
}
