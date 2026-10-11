package com.signdesk.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {
    @Test
    void validConfigurationDoesNotExposeCredentialsInDiagnostics() {
        var properties = new AuthProperties();
        properties.setUsername("fixture-admin");
        properties.setPassword("fixture-private-password");
        assertDoesNotThrow(properties::validate);
        assertFalse(properties.toString().contains("fixture-private-password"));
    }

    @Test
    void missingBlankOrExcessiveCredentialsFailWithAFixedSafeMessage() {
        for (String password : new String[] {null, " ", "fixture-private-password".repeat(20)}) {
            var properties = new AuthProperties();
            properties.setUsername("fixture-admin");
            properties.setPassword(password);
            var error = assertThrows(IllegalStateException.class, properties::validate);
            assertEquals("管理员登录配置缺失或超出范围，请检查 signdesk.auth", error.getMessage());
            assertFalse(error.getMessage().contains("fixture-private-password"));
        }
        for (String username : new String[] {null, " ", "x".repeat(65)}) {
            var properties = new AuthProperties();
            properties.setUsername(username);
            properties.setPassword("fixture-private-password");
            assertThrows(IllegalStateException.class, properties::validate);
        }
    }
}
