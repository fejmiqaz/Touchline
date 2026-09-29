package atrck.attendancetracker;

import atrck.attendancetracker.security.AllowedCoachOidcUserService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.*;
import static org.assertj.core.api.Assertions.*;

class AllowedCoachOidcUserServiceTests {
    private OidcUserRequest request(String email, Boolean verified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "stable-google-subject");
        if (email != null) claims.put("email", email);
        if (verified != null) claims.put("email_verified", verified);
        var now = Instant.now();
        var token = new OidcIdToken("id-token", now, now.plusSeconds(300), claims);
        var client = ClientRegistration.withRegistrationId("google").clientId("test")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token").scope("openid", "email").build();
        return new OidcUserRequest(client, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "access-token", now, now.plusSeconds(300)), token);
    }

    private AllowedCoachOidcUserService service(String allowed) {
        return new AllowedCoachOidcUserService(request -> new DefaultOidcUser(
                Set.of(new SimpleGrantedAuthority("ROLE_UNEXPECTED")), request.getIdToken()), allowed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"coach1@gmail.com", "coach2@gmail.com", "COACH1@GMAIL.COM"})
    void admitsOnlyApprovedVerifiedCoachesAndPreservesSubject(String email) {
        var user = service(" coach1@gmail.com, COACH2@gmail.com ").loadUser(request(email, true));
        assertThat(user.getName()).isEqualTo("stable-google-subject");
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_COACH");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ", ,"})
    void missingOrEmptyConfigurationDeniesAccess(String allowed) {
        assertDenied(allowed, "coach1@gmail.com", true);
    }

    @Test void rejectsUnknownEmailAndAliases() {
        assertDenied("coach1@gmail.com,coach2@gmail.com", "outsider@gmail.com", true);
        assertDenied("coach1@gmail.com", "coach1+alias@gmail.com", true);
    }

    @Test void rejectsMissingOrUnverifiedEmail() {
        assertDenied("coach1@gmail.com", null, true);
        assertDenied("coach1@gmail.com", "coach1@gmail.com", false);
        assertDenied("coach1@gmail.com", "coach1@gmail.com", null);
    }

    private void assertDenied(String allowed, String email, Boolean verified) {
        assertThatThrownBy(() -> service(allowed).loadUser(request(email, verified)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        error -> assertThat(error.getError().getErrorCode()).isEqualTo("access_denied"));
    }
}
