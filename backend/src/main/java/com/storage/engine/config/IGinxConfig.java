package com.storage.engine.config;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

@Configuration
public class IGinxConfig {

    private static final Logger log = LoggerFactory.getLogger(IGinxConfig.class);

    @Value("${iginx.host}")
    private String host;

    @Value("${iginx.port}")
    private int port;

    @Value("${iginx.username}")
    private String username;

    @Value("${iginx.password}")
    private String password;

    private Session session;

    @Bean
    public Session session() throws SessionException {
        session = new Session(host, port, username, password);
        session.openSession();
        log.info("IGinX session opened: {}:{}", host, port);
        return session;
    }

    @PreDestroy
    public void destroy() {
        if (session != null) {
            try {
                session.closeSession();
                log.info("IGinX session closed");
            } catch (SessionException e) {
                log.warn("Failed to close IGinX session: {}", e.getMessage());
            }
        }
    }
}
