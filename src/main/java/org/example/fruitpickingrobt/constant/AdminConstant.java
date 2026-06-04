package org.example.fruitpickingrobt.constant;

/**
 * 管理员相关常量字段
 * **/
public class AdminConstant {
    public static final String DEFAULT_AUTH_ERROR = "用户ID不存在/权限标识输入错误(仅支持admin/user)/不允许修改超级管理员权限";

    public static final String CHANGE_ROLE_SUCCESS= "用户权限修改成功，该用户权限已更新为【管理员/普通用户】";

    public static final String CHANGE_ROLE_ERROR = "用户ID不存在/权限标识输入错误(仅支持admin/user)/不允许修改超级管理员权限";

    public static final String DELETE_USER_SUCCESS = "用户删除成功";

    public static final String DELETE_USER_ERROR = "用户不存在/无法删除管理员账号/无权限操作";
}
