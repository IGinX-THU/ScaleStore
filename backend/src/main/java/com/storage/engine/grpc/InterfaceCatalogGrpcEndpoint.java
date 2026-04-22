package com.storage.engine.grpc;

import com.storage.engine.model.RestfulApiItem;
import com.storage.engine.service.GrpcApiCatalogService;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InterfaceCatalogGrpcEndpoint extends InterfaceCatalogServiceGrpc.InterfaceCatalogServiceImplBase {

    @Autowired
    private GrpcApiCatalogService grpcApiCatalogService;

    @Override
    public void listJavaGrpcInterfaces(InterfaceListRequest request,
                                       StreamObserver<InterfaceListResponse> responseObserver) {
        try {
            List<RestfulApiItem> items = grpcApiCatalogService.getAllJavaGrpcApis();
            InterfaceListResponse.Builder builder = InterfaceListResponse.newBuilder()
                    .setCode(200)
                    .setMessage("Success");
            for (RestfulApiItem item : items) {
                builder.addData(toProto(item));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(InterfaceListResponse.newBuilder()
                    .setCode(500)
                    .setMessage("Internal Error")
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getJavaGrpcInterface(InterfaceGetRequest request,
                                     StreamObserver<InterfaceResponse> responseObserver) {
        try {
            RestfulApiItem item = grpcApiCatalogService.getJavaGrpcApiById(request.getId());
            if (item == null) {
                responseObserver.onNext(InterfaceResponse.newBuilder()
                        .setCode(404)
                        .setMessage("Java gRPC API not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            responseObserver.onNext(InterfaceResponse.newBuilder()
                    .setCode(200)
                    .setMessage("Success")
                    .setData(toProto(item))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(InterfaceResponse.newBuilder()
                    .setCode(500)
                    .setMessage("Internal Error")
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void listPythonGrpcInterfaces(InterfaceListRequest request,
                                         StreamObserver<InterfaceListResponse> responseObserver) {
        try {
            List<RestfulApiItem> items = grpcApiCatalogService.getAllPythonGrpcApis();
            InterfaceListResponse.Builder builder = InterfaceListResponse.newBuilder()
                    .setCode(200)
                    .setMessage("Success");
            for (RestfulApiItem item : items) {
                builder.addData(toProto(item));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(InterfaceListResponse.newBuilder()
                    .setCode(500)
                    .setMessage("Internal Error")
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getPythonGrpcInterface(InterfaceGetRequest request,
                                       StreamObserver<InterfaceResponse> responseObserver) {
        try {
            RestfulApiItem item = grpcApiCatalogService.getPythonGrpcApiById(request.getId());
            if (item == null) {
                responseObserver.onNext(InterfaceResponse.newBuilder()
                        .setCode(404)
                        .setMessage("Python gRPC API not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            responseObserver.onNext(InterfaceResponse.newBuilder()
                    .setCode(200)
                    .setMessage("Success")
                    .setData(toProto(item))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(InterfaceResponse.newBuilder()
                    .setCode(500)
                    .setMessage("Internal Error")
                    .build());
            responseObserver.onCompleted();
        }
    }

    private InterfaceItem toProto(RestfulApiItem item) {
        if (item == null) {
            return InterfaceItem.getDefaultInstance();
        }

        return InterfaceItem.newBuilder()
                .setId(item.getId() == null ? 0 : item.getId())
                .setName(safe(item.getName()))
                .setUrl(safe(item.getUrl()))
                .setMethod(safe(item.getMethod()))
                .setDescription(safe(item.getDescription()))
                .setParamsExample(safe(item.getParamsExample()))
                .setResponseExample(safe(item.getResponseExample()))
                .setInvokeExample(safe(item.getCurlExample()))
                .build();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
