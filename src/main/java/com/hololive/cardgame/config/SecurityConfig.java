package com.hololive.cardgame.config;

import com.hololive.cardgame.security.JwtAuthenticationFilter;
import com.hololive.cardgame.service.CardAdminAccessService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(CardAdminProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CardAdminAccessService cardAdminAccessService;

    public SecurityConfig(
        JwtAuthenticationFilter jwtAuthenticationFilter,
        CardAdminAccessService cardAdminAccessService
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.cardAdminAccessService = cardAdminAccessService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/auth/**", "/actuator/**").permitAll()
                .requestMatchers("/api/users/**").authenticated()
                .requestMatchers("/api/matches/**").authenticated()
                .requestMatchers("/api/cards/**").authenticated()
                .requestMatchers("/api/decks/**").authenticated()
                .requestMatchers("/api/card-admin/**").access((authentication, context) ->
                    new AuthorizationDecision(cardAdminAccessService.isAllowed(extractUserId(authentication.get())))
                )
                .anyRequest().permitAll()
            )
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        if (principal instanceof String text && text.matches("\\d+")) {
            return Long.valueOf(text);
        }
        return null;
    }
}
