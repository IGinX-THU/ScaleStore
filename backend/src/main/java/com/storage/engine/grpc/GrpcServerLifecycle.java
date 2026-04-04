package com.storage.engine.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;

@Component
public class GrpcServerLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    @Autowired
    private InterfaceCatalogGrpcEndpoint interfaceCatalogGrpcEndpoint;

    @Autowired
    private ScaleStoreGrpcEndpoint scaleStoreGrpcEndpoint;

    private Server server;

    @PostConstruct
    public void start() throws IOException {
        server = ServerBuilder.forPort(grpcPort)
                .addService(interfaceCatalogGrpcEndpoint)
            .addService(scaleStoreGrpcEndpoint)
                .build()
                .start();
        logger.info("gRPC server started at port {}", grpcPort);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
            logger.info("gRPC server stopped");
        }
    }
}
