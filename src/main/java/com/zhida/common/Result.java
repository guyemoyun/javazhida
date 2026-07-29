package com.zhida.common;
//统一响应类
//{
// code:200
// msg:"nihao",
// data:{}
//}1
public class Result<T> {//T是泛型，能够匹配任何一种数据类型
    private  int code;//状态码
    private String msg;//提示信息
    private T data;//真正的数据


    //无参构造函数
    public Result(){}

    //有参构造函数
    public Result(int code,String msg,T data){
        this.code=code;
        this.msg=msg;
        this.data=data;
    }

    //静态工厂：成功 Result.ok()
    public static <T> Result<T>ok(T data){
        return new Result<>(ResultCode.SUCCESS.getCode(),
                ResultCode.SUCCESS.getMsg(),data
        );
    }

    public static <T> Result<T>ok(){
        return ok(null);
    }


    //静态工厂：失败 Result.fail()
    public static <T> Result<T> fail(int code,String msg){
        return new Result<>(code,msg,null);
    }

    public static <T> Result<T> fail(ResultCode rc){
        return new Result<>(rc.getCode(), rc.getMsg(), null);
    }

    //生成三个字段的getter/setter
    public T getData() {
        return data;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}