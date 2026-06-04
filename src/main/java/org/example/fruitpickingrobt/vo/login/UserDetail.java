package org.example.fruitpickingrobt.vo.login;

import lombok.Data;

@Data
public class UserDetail {
    /**
     * 用户ID
     */
    Long id;
    /**
     * 昵称/姓名
     */
    String nickname;
    /**
     * 角色 0-用户,1-管理员
     */
    Integer role;
    /**
     * 状态 0-禁用 1-正常
     */
    Integer status;
    /**
     * 创建时间
     */
    String createTime;
}
