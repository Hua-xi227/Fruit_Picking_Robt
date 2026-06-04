package org.example.fruitpickingrobt.entity;

import lombok.Data;

import java.time.LocalDateTime;
@Data
public class User {
    /**
     * 用户唯一 ID，自增
     **/
    Long id;
    /**
     * 邮箱（用于注册和接收验证码）
     **/
    String email;
    /**
     * 登录密码，加密存储（SHA256）
     **/
    String password;
    /**
     * 用户昵称/姓名
     **/
    String nickname;

    /**
     * 角色 0-用户,1-管理员
     **/
    Integer role;
    /**
     * 状态 0-禁用 1-正常
     **/
    Integer status;
    /**
     * 手机号
     **/
    String phone;
    /**
     * 创建时间
     **/
    LocalDateTime createTime;
    /**
     * 更新时间
     **/
    LocalDateTime updateTime;

}
