package com.easydeploy.backend.global.auth.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    private LocalDateTime createdAt;

    @Builder
    public User(String email, String password) {
        this.email = email;
        this.password = password;
        this.createdAt = LocalDateTime.now();
    }
}
