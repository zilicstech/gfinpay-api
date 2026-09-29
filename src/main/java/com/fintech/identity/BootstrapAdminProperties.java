package com.fintech.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record BootstrapAdminProperties(
        String code,
        String password,
        String mobile,
        String fullName,
        String email) {}
