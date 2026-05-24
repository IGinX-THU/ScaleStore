package com.storage.engine.model;

public class AddStorageEngineRequest {

    private String sourceType;
    private String ip;
    private Integer port;

    private String username;
    private String password;

    private String dummyDir;
    private Integer iginxPort;

    private String sizeCalculationStrategy;
    private String sshUsername;
    private String sshPassword;
    private Integer sshPort;

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDummyDir() {
        return dummyDir;
    }

    public void setDummyDir(String dummyDir) {
        this.dummyDir = dummyDir;
    }

    public Integer getIginxPort() {
        return iginxPort;
    }

    public void setIginxPort(Integer iginxPort) {
        this.iginxPort = iginxPort;
    }

    public String getSizeCalculationStrategy() {
        return sizeCalculationStrategy;
    }

    public void setSizeCalculationStrategy(String sizeCalculationStrategy) {
        this.sizeCalculationStrategy = sizeCalculationStrategy;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword;
    }

    public Integer getSshPort() {
        return sshPort;
    }

    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }
}
