package com.storage.engine.constant;

public enum ResultCode {
    SUCCESS(200, "成功"),
    CREATED(201, "创建成功"),
    UPDATED(200, "更新成功"),
    DELETED(200, "删除成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "未找到"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    USERNAME_EXISTS(4001, "用户名已存在"),
    NODE_LIMIT_EXCEEDED(4002, "节点数量已达上限（24个）"),
    NODE_NOT_FOUND(4003, "节点未找到"),
    USER_NOT_FOUND(4004, "用户未找到");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

