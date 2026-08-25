package com.wwjob.core.model;

import java.io.Serializable;

/**
 * @author 王威
 * @version 1.0
 */
public class ReturnT<T> implements Serializable {
    public static final int SUCCESS_CODE = 200;
    public static final int FAIL_CODE = 500;

    private int code;
    private String msg;
    private T data;

    public ReturnT() {}
    public ReturnT(int code, String msg) { this.code = code; this.msg = msg; }
    public ReturnT(int code, String msg, T data) { this.code = code; this.msg = msg; this.data = data; }

    public static <T> ReturnT<T> success() { return new ReturnT<>(SUCCESS_CODE, null); }
    public static <T> ReturnT<T> success(T data) { return new ReturnT<>(SUCCESS_CODE, null, data); }
    public static <T> ReturnT<T> fail(String msg) { return new ReturnT<>(FAIL_CODE, msg); }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
