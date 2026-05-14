package com.example.security_demo.dto;

import com.example.security_demo.domain.UserEntity;
import com.example.security_demo.domain.UserRole;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.crypto.password.PasswordEncoder;

@Getter
@Setter
@NoArgsConstructor
public class UserJoinRequest {

    private String username;
    private String password;

    public UserEntity toEntity(PasswordEncoder passwordEncoder) {
        return UserEntity.builder()
                .username(this.username)
                .password(passwordEncoder.encode(this.password))
                .role(UserRole.USER)
                .build();
    }
}
