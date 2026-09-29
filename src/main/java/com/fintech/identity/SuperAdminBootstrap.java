package com.fintech.identity;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties props;

    public SuperAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, BootstrapAdminProperties props) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String code = required(props.code(), "app.bootstrap.admin.code");
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM users WHERE code = ?", Integer.class, code);
        if (existing != null && existing > 0) {
            return;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'SUPER_ADMIN', ?, ?, ?, ?, 'ACTIVE', ?)
                """, id, required(props.fullName(), "app.bootstrap.admin.full-name"),
                required(props.mobile(), "app.bootstrap.admin.mobile"),
                required(props.email(), "app.bootstrap.admin.email"),
                passwordEncoder.encode(required(props.password(), "app.bootstrap.admin.password")),
                code);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'SUPER_ADMIN'", id);
        log.info("BOOTSTRAP created super admin code={}", code);
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value.trim();
    }
}
