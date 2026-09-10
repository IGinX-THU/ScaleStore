package com.storage.engine.constant;

public class IGinxConstants {
    public static final String USERS_PATH = "sys.user";
    public static final String CLUSTER_PATH = "sys.cluster";
    public static final String NODES_PATH = "sys.node";
    public static final String POLICY_PATH = "sys.policy";
    public static final String BOOTSTRAP_PATH = "sys.bootstrap";
    public static final String DATASOURCE_PATH = "sys.datasource";

    // Storage metadata path
    public static final String STORAGE_META_PATH = "storage.meta";
    // RESTful interface persistence path
    public static final String INTERFACE_RESTFUL_PATH = "interface.restful";
    public static final String INTERFACE_JAVA_GRPC_PATH = "interface.java_grpc";
    public static final String INTERFACE_PYTHON_GRPC_PATH = "interface.python_grpc";
    // Data path prefix for actual data
    public static final String DATA_PATH_PREFIX = "data";

    // Data types
    public static final String TYPE_TIMESERIES = "timeseries";
    public static final String TYPE_RELATIONAL = "relational";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_KEYVALUE = "keyvalue";
    public static final String TYPE_DIRECTORY = "directory";
}
