package org.example.fruitpickingrobt.utils;
/**
 * 上下文基类，封装了当前请求的一些东西
 * **/
public class BaseContext {

    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentJwt = new ThreadLocal<>();

    public static void setCurrentUserId(Long userId) {
        currentUserId.set(userId);
    }
    public static void setCurrentJwt(String jwt) {
        currentJwt.set(jwt);
    }
    public static Long getCurrentUserId() {
        return currentUserId.get();
    }
    public static String getCurrentJwt() {
        return currentJwt.get();
    }
    public static void clear() {
        currentUserId.remove();
        currentJwt.remove();
    }
}
