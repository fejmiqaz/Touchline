package atrck.attendancetracker.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Grants coach access only after Google identity loading and explicit email approval. */
public final class AllowedCoachOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final Set<String> allowedEmails;

    public AllowedCoachOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, String emails) {
        this.delegate = delegate;
        this.allowedEmails = Arrays.stream((emails == null ? "" : emails).split(","))
                .map(String::strip).filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = delegate.loadUser(request);
        // Use the validated Google ID token's claims, not form input or an unverified email.
        String email = user.getIdToken().getEmail();
        if (!"google".equals(request.getClientRegistration().getRegistrationId())
                || !Boolean.TRUE.equals(user.getIdToken().getEmailVerified())
                || email == null || !allowedEmails.contains(email.strip().toLowerCase(Locale.ROOT))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                    "This Google account is not authorized to access the coach workspace.");
        }
        // Keep Google's subject as the principal name so existing coach-owned data stays accessible.
        return new DefaultOidcUser(Set.of(new SimpleGrantedAuthority("ROLE_COACH")),
                user.getIdToken(), user.getUserInfo());
    }
}
