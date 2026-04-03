package com.storage.engine.model;

import com.storage.engine.constant.ResultCode;

public class Response<T> {
    private Integer code;
    private String message;
    private T data;

    public Response() {
    }

    public Response(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Response<T> success(T data) {
        return new Response<>(200, "Success", data);
    }

    public static <T> Response<T> success() {
        return new Response<>(200, "Success", null);
    }

    public static <T> Response<T> error(ResultCode resultCode) {
        return new Response<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Response<T> error(Integer code, String message) {
        return new Response<>(code, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
