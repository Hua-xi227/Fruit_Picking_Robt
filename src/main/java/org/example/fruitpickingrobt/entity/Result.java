package org.example.fruitpickingrobt.entity;
import org.example.fruitpickingrobt.constant.ErrorConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

@Data
public class Result<T> {
    // 结果成功代码
    public static final Long SUCCESS_CODE = 200L;
    // 结果失败代码
    public static final Long FAIL_CODE = 400L;
    // 未授权代码
    public static final Long UNAUTHORIZED_CODE = 401L;
    // 禁止访问代码
    public static final Long FORBIDDEN_CODE = 403L;

    /**
     * 标准成功结果
     **/
    public static final Result<?> SUCCESS = new Result<>(SUCCESS_CODE);
    /**
     * 标准失败结果
     **/
    public static final Result<?> FAIL = new Result<>(FAIL_CODE);

    /**
     * 静态方法封装构造
     **/
    public static Result<?> failure(String errorMsg) {
        Result<?> result = new Result<>(FAIL_CODE);
        result.errorMsg = errorMsg;
        return result;
    }

    public static <T> Result<T> failure(T data, String errorMsg) {
        Result<T> result = new Result<>(FAIL_CODE);
        result.errorMsg = errorMsg;
        result.data = data;
        return result;
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>(SUCCESS_CODE);
        result.data = data;
        return result;
    }

    public static Result<?> success(String errorMsg) {
        Result<?> result = new Result<>(SUCCESS_CODE);
        result.setErrorMsg(errorMsg);
        return result;
    }

    public static Result<?> unauthorized() {
        Result<?> result = new Result<>(UNAUTHORIZED_CODE);
        result.setErrorMsg(ErrorConstant.UNAUTHORIZED);
        return result;
    }

    public static Result<?> forbidden(String errorMsg) {
        Result<?> result = new Result<>(FORBIDDEN_CODE);
        result.setErrorMsg(errorMsg);
        return result;
    }

    private Result() {}

    private Result(Long code) {
        this.success = code;
    }

    /**
     * 返回状态
     **/
    private Long success;
    /**
     * 错误信息
     **/
    private String errorMsg;
    /**
     * 返回数据
     **/
    private T data;
    /**
     * total
     **/
    private Long total;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 直接将本对象封装为json
    public String asJsonString() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
