package atrck.attendancetracker.security;

import java.time.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    Clock clock(@Value("${app.time-zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean
    AllowedCoachOidcUserService allowedCoachUserService(@Value("${app.allowed-coach-emails:}") String emails) {
        return new AllowedCoachOidcUserService(new OidcUserService(), emails);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AllowedCoachOidcUserService allowedCoachUserService) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/login", "/app.css", "/error").permitAll().anyRequest().hasRole("COACH"))
                .oauth2Login(oauth -> oauth.loginPage("/login").defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .userInfoEndpoint(info -> info.oidcUserService(allowedCoachUserService)))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout")).build();
    }
}
