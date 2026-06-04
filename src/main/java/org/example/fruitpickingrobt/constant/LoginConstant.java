package org.example.fruitpickingrobt.constant;

import java.util.Arrays;
import java.util.List;

public class LoginConstant {
    // 验证码相关
    public static final Integer VERIFY_CODE_RANDOM_START = 0;
    public static final Integer VERIFY_CODE_RANDOM_END = 1000000;
    public static final Integer VERIFY_CODE_LENGTH = 6;
    public static final int CODE_TYPE_REGISTER = 1;
    public static final int CODE_TYPE_REST_PASSWORD = 2;
    public static final int CODE_TYPE_CHANGE_PASSWORD = 3;
    public static final int CODE_TYPE_BIND_PHONE = 4;  // 新增：绑定手机号

    public static final List<Integer> CODE_TYPE_LIST =
            Arrays.asList(CODE_TYPE_REGISTER, CODE_TYPE_REST_PASSWORD, CODE_TYPE_CHANGE_PASSWORD, CODE_TYPE_BIND_PHONE);

    // redis缓存前缀
    public static final String REGISTER_CODE_CACHE_PREFIX = "login:code:register:";
    public static final String RESET_PASSWORD_CODE_CACHE_PREFIX = "login:code:findPassword:";
    public static final String CHANGE_PASSWORD_CODE_CACHE_PREFIX = "login:code:changePassword:";
    public static final String BIND_PHONE_CODE_CACHE_PREFIX = "login:code:bindPhone:";
    public static final String TOKEN_CACHE_PREFIX = "login:token:";

    // 提示信息
    public static final String CODE_SENT = "验证码已发送，5分钟内有效";
    public static final String CODE_ERROR = "邮箱格式错误/1分钟内已发送验证码，请勿重复获取";
    public static final String REGISTER_ERROR = "邮箱已被注册/用户名已被占用/验证码错误/密码格式不符合要求";
    public static final String LOGIN_ERROR = "账号不存在/密码错误/账号已注销";

    public static final String TOKEN_EXPIRE_TIME = "24小时";

    public static final String CHANGE_PASSWORD_SUCCESS = "密码修改成功，请重新登录";
    public static final String CHANGE_PASSWORD_ERROR = "原密码错误/验证码错误/新密码格式不符合要求";

    public static final String RESET_PASSWORD_SUCCESS = "密码重置成功，请使用新密码登录";
    public static final String RESET_PASSWORD_ERROR = "该邮箱未注册/验证码错误/新密码格式不符合要求";

}
