package org.example.fruitpickingrobt.controller;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.ObjectUtil;
import org.example.fruitpickingrobt.constant.LoginConstant;
import org.example.fruitpickingrobt.dao.LoginDAO;
import org.example.fruitpickingrobt.dto.*;
import org.example.fruitpickingrobt.dto.user.*;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.entity.User;
import org.example.fruitpickingrobt.service.login.LoginService;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.example.fruitpickingrobt.utils.StringUtil;
import org.example.fruitpickingrobt.vo.login.LoginVO;
import org.example.fruitpickingrobt.vo.login.UserDetail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LoginController {

    @Autowired
    private LoginService loginService;
    @Autowired
    private LoginDAO loginDAO;

    /**
     * 邮箱验证码获取（注册、重置密码、修改密码）
     */
    @PostMapping("/codes/email")
    public Result<?> sendEmailCode(@RequestBody CodeDTO codeDTO) {
        if (ObjectUtil.hasEmpty(codeDTO.getEmail(), codeDTO.getType())
                || !Validator.isEmail(codeDTO.getEmail())) {
            return Result.failure(LoginConstant.CODE_ERROR);
        }

        if (!LoginConstant.CODE_TYPE_LIST.contains(codeDTO.getType())) {
            return Result.failure("验证码类型错误");
        }

        String code = loginService.sendCode(codeDTO);
        return code != null ? Result.success(LoginConstant.CODE_SENT) : Result.failure(LoginConstant.CODE_ERROR);
    }

    /**
     * 用户注册（使用邮箱注册）
     */
    @PostMapping("/users")
    public Result<?> register(@RequestBody RegisterDTO registerDTO) {
        if (ObjectUtil.hasEmpty(registerDTO.getEmail(), registerDTO.getNickname(),
                registerDTO.getPassword(), registerDTO.getNickname(), registerDTO.getVerifyCode())
                || !Validator.isEmail(registerDTO.getEmail())
                || StringUtil.isInvalidPassword(registerDTO.getPassword())
                || StringUtil.isInvalidNickname(registerDTO.getNickname())) {
            return Result.failure(LoginConstant.REGISTER_ERROR);
        }
        UserDetail userDetail = loginService.register(registerDTO);
        return userDetail != null ? Result.success(userDetail) : Result.failure(LoginConstant.REGISTER_ERROR);
    }

    /**
     * 用户登录（使用电话/邮箱+密码）
     */
    @PostMapping("/users/login")
    public Result<?> login(@RequestBody LoginDTO loginDTO) {
        if (ObjectUtil.hasEmpty(loginDTO.getAccount(), loginDTO.getPassword())) {
            return Result.failure(LoginConstant.LOGIN_ERROR);
        }

        LoginVO loginVO = loginService.login(loginDTO);
        return loginVO != null ? Result.success(loginVO) : Result.failure(LoginConstant.LOGIN_ERROR);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/users/{userId}")
    public Result<?> getCurrentUserDetail(@PathVariable Long userId) {
        Long currentUserId = BaseContext.getCurrentUserId();
        if (currentUserId == null) {
            return Result.unauthorized();
        }
        if (!currentUserId.equals(userId)) {
            return Result.forbidden("无权访问其他用户信息");
        }
        UserDetail detail = loginService.getUserDetailById(userId);
        return Result.success(detail);
    }

    /**
     * 修改密码（需要登录）
     */
    @PostMapping("/users/{userId}/password")
    public Result<?> changePassword(@PathVariable Long userId, @RequestBody ChangePasswordDTO changePasswordDTO) {
        Long currentUserId = BaseContext.getCurrentUserId();
        if (currentUserId == null) {
            return Result.unauthorized();
        }

        if (!currentUserId.equals(userId)) {
            return Result.forbidden("无权修改其他用户密码");
        }

        if (ObjectUtil.hasEmpty(changePasswordDTO.getOldPassword(),
                changePasswordDTO.getNewPassword(), changePasswordDTO.getVerifyCode())
                || StringUtil.isInvalidPassword(changePasswordDTO.getNewPassword())) {
            return Result.failure(LoginConstant.CHANGE_PASSWORD_ERROR);
        }

        boolean success = loginService.changePassword(changePasswordDTO, userId);
        return success ? Result.success(LoginConstant.CHANGE_PASSWORD_SUCCESS)
                : Result.failure(LoginConstant.CHANGE_PASSWORD_ERROR);
    }

    /**
     * 重置密码（忘记密码，通过邮箱验证）
     */
    @PostMapping("/password-reset")
    public Result<?> resetPassword(@RequestBody ResetPasswordDTO resetPasswordDTO) {
        if (ObjectUtil.hasEmpty(resetPasswordDTO.getEmail(),
                resetPasswordDTO.getNewPassword(), resetPasswordDTO.getVerifyCode())
                || !Validator.isEmail(resetPasswordDTO.getEmail())
                || StringUtil.isInvalidPassword(resetPasswordDTO.getNewPassword())) {
            return Result.failure(LoginConstant.RESET_PASSWORD_ERROR);
        }

        boolean success = loginService.resetPassword(resetPasswordDTO);
        return success ? Result.success(LoginConstant.RESET_PASSWORD_SUCCESS)
                : Result.failure(LoginConstant.RESET_PASSWORD_ERROR);
    }

    /**
     * 退出登录
     */
    @PostMapping("/users/logout")
    public Result<?> logout() {
        String jwt = BaseContext.getCurrentJwt();
        if (jwt != null) {
            loginService.logout(jwt);
        }
        return Result.success("退出成功");
    }

    /**
     * 修改个人信息
     */
    @PostMapping("/users/info")
    public Result<?> updateUserInfo(@RequestBody UpdateUserInfoDTO updateUserInfoDTO) {
        System.out.println("接收到的 DTO: phone=" + updateUserInfoDTO.getPhone() +
                ", nickname=" + updateUserInfoDTO.getNickname());
        Long userId = BaseContext.getCurrentUserId();
        System.out.println("当前登录用户ID = " + userId);
        return loginService.updateUserInfo(userId,updateUserInfoDTO);
    }

    /**
     * 注销用户
     */
    @DeleteMapping("/users/canel")
    public Result<?> cancelUser() {
        Long userId = BaseContext.getCurrentUserId();
        return loginService.cancelUser(userId);
    }

    /**
     * 普通用户变为管理员
     */
    @PostMapping("/admin/users/{userId}/promote")
    public Result<?> promoteToAdmin(@PathVariable Long userId, @RequestHeader("Authorization") String token) {
        // 验证当前登录用户是否为管理员（从 token 中解析 role）
        Long currentUserId = BaseContext.getCurrentUserId();
        User currentUser = loginDAO.selectUserById(currentUserId);
        if (currentUser.getRole() != 1) {
            return Result.forbidden("无权限");
        }
        loginDAO.updateUserRole(userId, 1);
        return Result.success("已提升为管理员");
    }
}
