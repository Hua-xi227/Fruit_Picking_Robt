package org.example.fruitpickingrobt.dto;

import lombok.Data;

@Data
public class RegisterDTO {
    /**
     * 邮箱（用于注册和接收验证码）
     */
    String email;
    /**
     * 手机号（用于登录）
     */
    String phone;
    /**
     * 密码
     */
    String password;
    /**
     * 姓名/昵称
     */
    String nickname;
    /**
     * 验证码
     */
    String verifyCode;
}