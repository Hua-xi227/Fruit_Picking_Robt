package org.example.fruitpickingrobt.dto;

import lombok.Data;

@Data
public class LoginDTO {
    /**
     * 可以是邮箱或手机号（用于登录）
     */
    String account;
    /**
     * 密码
     */
    String password;
}