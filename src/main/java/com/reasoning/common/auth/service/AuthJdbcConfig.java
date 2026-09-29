package com.reasoning.common.auth.service;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

@Configuration(proxyBeanMethods = false)
class AuthJdbcConfig {
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new InstantJdbcTemplate(dataSource);
    }

    /** PostgreSQL JDBC cannot infer the SQL type of Instant; bind it as TIMESTAMP WITH TIME ZONE. */
    static final class InstantJdbcTemplate extends JdbcTemplate {
        InstantJdbcTemplate(DataSource source) {
            super(source);
        }

        @Override
        protected PreparedStatementSetter newArgPreparedStatementSetter(Object[] args) {
            Object[] converted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                converted[i] = args[i] instanceof Instant instant ? Timestamp.from(instant) : args[i];
            }
            return super.newArgPreparedStatementSetter(converted);
        }
    }
}
