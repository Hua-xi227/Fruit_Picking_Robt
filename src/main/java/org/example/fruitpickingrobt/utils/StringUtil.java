package org.example.fruitpickingrobt.utils;
/**
 * 字符串判空工具类
 * **/
public class StringUtil {
    public static final String WHITESPACE = " ";
    public static final String EMPTY = "";
    public static final String JS_UNDEFINED = "undefined";

    /**
     * 判断字符串是否为空
     * **/
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    /**
     * 字符串不为空
     * **/
    public static boolean isNotEmpty(String str) {
        return str != null && !str.trim().isEmpty();
    }
    /**
     * 密码是否非法
     * **/
    public static boolean isInvalidPassword(String s) {
        return !s.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[$@$!%*?&])[A-Za-z\\d$@$!%*?&]{8,16}");
    }
    /**
     * 昵称是否非法
     * **/
    public static boolean isInvalidNickname(String s) {
        return false;
    }
}
