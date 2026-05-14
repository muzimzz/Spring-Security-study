package com.example.security_demo.service;

import com.example.security_demo.domain.UserEntity;
import com.example.security_demo.dto.UserJoinRequest;
import com.example.security_demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public void join(UserJoinRequest request) {

        // Todo: username 중복 검증 로직
        // if (userRepository.findByUserName)

        userRepository.save(request.toEntity(passwordEncoder));
        // Todo: 성공에 따른 return값 추가 (void X)
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username + " 사용자를 찾을 수 없습니다."));
        return User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .roles(user.getRole().name())
                .build();
    }
}
