package com.storage.engine.model;

public class RestfulApiItem {

    private Integer id;
    private String name;
    private String url;
    private String method;
    private String description;
    private String paramsExample;
    private String responseExample;
    private String curlExample;
    private Boolean isValid;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParamsExample() {
        return paramsExample;
    }

    public void setParamsExample(String paramsExample) {
        this.paramsExample = paramsExample;
    }

    public String getResponseExample() {
        return responseExample;
    }

    public void setResponseExample(String responseExample) {
        this.responseExample = responseExample;
    }

    public String getCurlExample() {
        return curlExample;
    }

    public void setCurlExample(String curlExample) {
        this.curlExample = curlExample;
    }

    public Boolean getIsValid() {
        return isValid;
    }

    public void setIsValid(Boolean valid) {
        isValid = valid;
    }
}
