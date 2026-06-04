package org.example.fruitpickingrobt.dto.user;

import lombok.Data;

@Data
public class UpdateUserInfoDTO {
    /**
     * 昵称
     */
    private String nickname;
    /**
     * 手机号
     */
    private String phone;
    /**
     * 邮箱
     */
    private String email;
}
