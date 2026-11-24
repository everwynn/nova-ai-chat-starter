package io.github.everwynn.nova.ai.starter.web;

/**
 * HTTP 统一响应结构，{@code code=0} 表示成功。
 */
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public Result() {
    }

    private Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> fail(String msg) {
        return new Result<>(1, msg, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
