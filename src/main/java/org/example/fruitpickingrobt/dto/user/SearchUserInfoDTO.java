package org.example.fruitpickingrobt.dto.user;

import lombok.Data;

/**
 * 用于返回通过username查找到的用户信息
 */
@Data
public class SearchUserInfoDTO {
    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String email;
}
