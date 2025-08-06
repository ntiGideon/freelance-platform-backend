package com.group2.userservice.awsmicroservicegroup2.filters;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.ControllerAdvice;


@ControllerAdvice
@EnableWebSecurity
public class OAuth2LoginSecurityConfig {
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated()).oauth2Login(Customizer.withDefaults());
        return http.build();
    }
}
