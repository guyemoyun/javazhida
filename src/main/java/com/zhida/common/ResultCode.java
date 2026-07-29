package com.zhida.common;

public enum ResultCode {
    SUCCESS(200,"成功"),
    BAD_REQUEST(400,"参数请求错误"),
    UNAUTHORIZED(401,"未登录或登录已过期"),
    ERROR(500,"服务器开小差了");

    //定义2个字段 Code msg
    private final int code;
    private final String msg;

    //构造函数
    ResultCode(int code,String msg){
        //把参数赋值给字段
        this.code=code;
        this.msg=msg;
    }

    //快速给字段生成getter/setter

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
    //鼠标右键=>菜单=>getter/setter
    //按住shift选中全部字段=>OK
}
