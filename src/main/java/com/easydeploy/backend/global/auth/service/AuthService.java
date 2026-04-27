package com.easydeploy.backend.global.auth.service;

import com.easydeploy.backend.global.auth.domain.User;
import com.easydeploy.backend.global.auth.repository.UserRepository;
import com.easydeploy.backend.global.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public String register(String email, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .build();
        userRepository.save(user);
        return jwtUtil.generateToken(email);
    }

    public String login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return jwtUtil.generateToken(email);
    }
}
