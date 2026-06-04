package org.example.fruitpickingrobt.utils;

import cn.hutool.crypto.digest.DigestUtil;

public class PasswordUtil {
    /**
     * 对明文密码进行加密（用于注册）
     * @param plainPassword 用户输入的原始密码
     * **/
    public static String encodePassword(String plainPassword) {
        return DigestUtil.sha256Hex(plainPassword);
    }
    /**
     * 验证用户输入密码是否匹配存储的哈希值（用于登录）
     * @param plainPassword 用户输入的明文密码
     * @param encodedPassword 数据库中的加密密码
     * **/
    public static boolean verifyPassword(String plainPassword, String encodedPassword) {
        if (plainPassword == null || encodedPassword == null) {
            return false;
        }
        return DigestUtil.sha256Hex(plainPassword).equals(encodedPassword);
    }

}
