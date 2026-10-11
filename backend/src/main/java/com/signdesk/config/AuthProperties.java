package com.signdesk.config;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "signdesk.auth")
public class AuthProperties {
    private String username;
    private String password;

    @PostConstruct
    void validate() {
        // A Bean Validation binding failure can print the rejected configuration value.
        // Keep startup diagnostics fixed so even an invalid password is never logged.
        if (username == null || username.isBlank() || username.length() > 64
                || password == null || password.isBlank() || password.length() > 256) {
            throw new IllegalStateException("管理员登录配置缺失或超出范围，请检查 signdesk.auth");
        }
    }
}
