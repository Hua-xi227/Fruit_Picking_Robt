package org.example.fruitpickingrobt.service.login;

import org.example.fruitpickingrobt.dto.user.*;
import org.example.fruitpickingrobt.dto.ChangePasswordDTO;
import org.example.fruitpickingrobt.dto.CodeDTO;
import org.example.fruitpickingrobt.dto.LoginDTO;
import org.example.fruitpickingrobt.dto.RegisterDTO;
import org.example.fruitpickingrobt.dto.ResetPasswordDTO;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.vo.login.LoginVO;
import org.example.fruitpickingrobt.vo.login.UserDetail;

public interface LoginService {

    String sendCode(CodeDTO codeDTO);


    UserDetail register(RegisterDTO registerDTO);

    LoginVO login(LoginDTO loginDTO);

/**
 * 根据用户ID获取用户详细信息
 *
 * @param userId 用户ID，用于标识唯一用户
 * @return 返回UserDetail对象，包含用户的详细信息
 */
    UserDetail getUserDetailById(Long userId);

    boolean changePassword(ChangePasswordDTO changePasswordDTO, Long userId);

    boolean resetPassword(ResetPasswordDTO resetPasswordDTO);


    // 添加退出登录方法
    void logout(String jwt);

    // 修改个人信息
    Result<?> updateUserInfo(Long userId, UpdateUserInfoDTO updateUserInfoDTO);

    //用户注销（status=0）同时清除该用户关联的家属绑定关系
    Result<?> cancelUser(Long userId);
}
