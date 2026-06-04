package org.example.fruitpickingrobt.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.fruitpickingrobt.entity.User;
@Mapper
public interface LoginDAO {
    void insertUser(User user);

    User selectUserByUsername(String username);

    User selectUserByEmail(String email);  // 新增

    User selectUserById(Long id);

/**
 * 根据用户ID更新用户信息
 * 该方法用于更新系统中已存在的用户信息
 *
 * @param user 包含更新后用户信息的User对象，其中应包含要更新的用户ID
 */
    void updateUserById(User user);

    void updateUserByUsername(User user);

    int checkUsernameExists(String username);  // 新增

    int checkEmailExists(String email);  // 新增
    // 根据手机号查询用户
    User selectUserByPhone(@Param("phone") String phone);
    // 动态更新用户信息（仅更新传入的非空字段）
    int updateUserSelective(User user);
    // 用户注销（将status字段置为0）
    int logout(@Param("userId") Long userId);
    // 更新用户权限
    int updateUserRole(@Param("userId") Long userId,@Param("role") Integer role);
    // 查询所有用户总数
    int countAllUsers();
}
