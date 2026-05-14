package com.example.security_demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf.disable()); // 테스트를 위해 CSRF 비활성화

//        http.authorizeHttpRequests((auth) -> auth
//                .requestMatchers("/", "/login").permitAll()
//                .requestMatchers("/admin").hasRole("ADMIN")
//                .requestMatchers("/my/**").hasAnyRole("ADMIN", "USER")
//                .anyRequest().authenticated()
//        );
//
        // 시큐리티가 제공하는 기본 로그인 폼 사용
        http.formLogin((login) -> login
                .loginPage("/login")
                .loginProcessingUrl("/login")
                // .permitAll()
        );

        return http.build();
    }
}
