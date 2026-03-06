package com.storage.engine.config;

import cn.edu.tsinghua.iginx.session.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IGinxConfig {

    @Value("${iginx.host}")
    private String host;

    @Value("${iginx.port}")
    private int port;

    @Value("${iginx.username}")
    private String username;

    @Value("${iginx.password}")
    private String password;

    @Bean
    public Session session() {
        Session session = new Session(host, port, username, password);
        try {
            session.openSession();
        } catch (Exception e) {
            throw new RuntimeException("Failed to open IGinX session", e);
        }
        return session;
    }
}
