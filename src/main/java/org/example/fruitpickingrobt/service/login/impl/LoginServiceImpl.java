package org.example.fruitpickingrobt.service.login.impl;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.constant.ErrorConstant;
import org.example.fruitpickingrobt.constant.LoginConstant;
import org.example.fruitpickingrobt.dao.LoginDAO;
import org.example.fruitpickingrobt.dto.user.*;
import org.example.fruitpickingrobt.dto.ChangePasswordDTO;
import org.example.fruitpickingrobt.dto.CodeDTO;
import org.example.fruitpickingrobt.dto.LoginDTO;
import org.example.fruitpickingrobt.dto.RegisterDTO;
import org.example.fruitpickingrobt.dto.ResetPasswordDTO;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.entity.User;
import org.example.fruitpickingrobt.service.Vioce.EmailService;
import org.example.fruitpickingrobt.service.login.LoginService;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.example.fruitpickingrobt.utils.JwtUtil;
import org.example.fruitpickingrobt.utils.PasswordUtil;
import org.example.fruitpickingrobt.utils.TimeUtil;
import org.example.fruitpickingrobt.vo.login.LoginVO;
import org.example.fruitpickingrobt.vo.login.UserDetail;
import org.example.fruitpickingrobt.config.SecurityProperties;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private LoginDAO loginDAO;
    @Autowired
    private SecurityProperties securityProperties;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private EmailService emailService;

    @Override
    public String sendCode(CodeDTO codeDTO) {
        return sendAndSaveCode(codeDTO);
    }


    @Override
    public UserDetail register(RegisterDTO registerDTO) {
        if (!checkCode(LoginConstant.CODE_TYPE_REGISTER, registerDTO.getEmail(), registerDTO.getVerifyCode())) {
            log.warn("注册失败：验证码错误，邮箱={}", registerDTO.getEmail());
            return null;
        }

        if (loginDAO.checkEmailExists(registerDTO.getEmail()) > 0) {
            log.warn("注册失败：邮箱已存在，邮箱={}", registerDTO.getEmail());
            return null;
        }

        if (loginDAO.checkUsernameExists(registerDTO.getNickname()) > 0) {
            log.warn("注册失败：用户名已存在，用户名={}", registerDTO.getNickname());
            return null;
        }

        //判断是否是第一个用户
        int totalUsers = loginDAO.countAllUsers();
        boolean isFirstUser = (totalUsers == 0);

        registerDTO.setPassword(PasswordUtil.encodePassword(registerDTO.getPassword()));

        User user = new User();
        BeanUtils.copyProperties(registerDTO, user);
        user.setEmail(registerDTO.getEmail());
        user.setStatus(1);
        user.setRole(isFirstUser ? 1 : 0); // 第一个用户为管理员，后续为普通用户
        loginDAO.insertUser(user);

        // 6. 输出提示（日志 + 可选返回消息）
        if (isFirstUser) {
            log.info("第一个用户注册成功，自动授予管理员权限。邮箱：{}，昵称：{}", user.getEmail(), user.getNickname());
            // 如果希望在前端提示，可以在返回的 userDetail 中增加一个字段，或者直接记录日志
        } else {
            log.info("新用户注册成功，角色为普通用户。邮箱：{}", user.getEmail());
        }

        user = loginDAO.selectUserById(user.getId());
        UserDetail userDetail = new UserDetail();
        BeanUtils.copyProperties(user, userDetail);
        userDetail.setCreateTime(TimeUtil.toString(user.getCreateTime()));
        return userDetail;
    }

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        String account = loginDTO.getAccount();
        String password = PasswordUtil.encodePassword(loginDTO.getPassword());
        User user = null;
        //判断account是邮箱还是手机号（判断包含@则为邮箱）
        if (account.contains("@")) {
            user = loginDAO.selectUserByEmail(account);
        } else {
            user = loginDAO.selectUserByPhone(account);
        }
        if (user == null || !user.getPassword().equals(password)) {
            log.warn("登录失败：用户不存在/密码错误，account={}", account);
            return null;
        }

        if (user.getStatus() == 0) {
            log.warn("账号已注销，无法登录，userId={}", user.getId());
            return null;
        }

        LoginVO loginVO = new LoginVO();
        String token = createTokenAndSave(user);

        BeanUtils.copyProperties(user, loginVO);
        loginVO.setToken(token);
        return loginVO;
    }

    @Override
    public UserDetail getUserDetailById(Long userId) {
        User user = loginDAO.selectUserById(userId);
        if (user == null) return null;
        UserDetail userDetail = new UserDetail();
        BeanUtils.copyProperties(user, userDetail);
        userDetail.setCreateTime(TimeUtil.toString(user.getCreateTime()));
        return userDetail;
    }

    @Override
    public boolean changePassword(ChangePasswordDTO changePasswordDTO, Long userId) {
        changePasswordDTO.setOldPassword(PasswordUtil.encodePassword(changePasswordDTO.getOldPassword()));
        changePasswordDTO.setNewPassword(PasswordUtil.encodePassword(changePasswordDTO.getNewPassword()));

        User user = loginDAO.selectUserById(userId);
        if (user == null) return false;

        if (!Objects.equals(changePasswordDTO.getOldPassword(), user.getPassword())) {
            return false;
        }

        if (!checkCode(LoginConstant.CODE_TYPE_CHANGE_PASSWORD, user.getEmail(), changePasswordDTO.getVerifyCode())) {
            return false;
        }

        User newPasswordUser = new User();
        newPasswordUser.setId(userId);
        newPasswordUser.setPassword(changePasswordDTO.getNewPassword());
        loginDAO.updateUserById(newPasswordUser);

        stringRedisTemplate.delete(LoginConstant.TOKEN_CACHE_PREFIX + BaseContext.getCurrentJwt());
        return true;
    }

    @Override
    public boolean resetPassword(ResetPasswordDTO resetPasswordDTO) {
        User user = loginDAO.selectUserByEmail(resetPasswordDTO.getEmail());
        if (user == null) {
            return false;
        }

        if (!checkCode(LoginConstant.CODE_TYPE_REST_PASSWORD, resetPasswordDTO.getEmail(), resetPasswordDTO.getVerifyCode())) {
            return false;
        }

        resetPasswordDTO.setNewPassword(PasswordUtil.encodePassword(resetPasswordDTO.getNewPassword()));

        User updateUser = new User();
        updateUser.setId(user.getId());
        updateUser.setPassword(resetPasswordDTO.getNewPassword());
        loginDAO.updateUserById(updateUser);

        return true;
    }

    @Override
    public void logout(String jwt) {
        if (jwt != null) {
            stringRedisTemplate.delete(LoginConstant.TOKEN_CACHE_PREFIX + jwt);
        }
    }

    // 创建token并存入Redis
    private String createTokenAndSave(User user) {
        String token = jwtUtil.createJwt(user.getId(), user.getNickname(), user.getRole().toString());
        stringRedisTemplate.opsForValue().set(LoginConstant.TOKEN_CACHE_PREFIX + token, String.valueOf(user.getId()),
                securityProperties.getTokenValiditySeconds());
        return token;
    }

    private boolean checkCode(int type, String target, String code) {
        String key;
        switch (type) {
            case LoginConstant.CODE_TYPE_REGISTER:
                key = LoginConstant.REGISTER_CODE_CACHE_PREFIX + target;
                break;
            case LoginConstant.CODE_TYPE_REST_PASSWORD:
                key = LoginConstant.RESET_PASSWORD_CODE_CACHE_PREFIX + target;
                break;
            case LoginConstant.CODE_TYPE_CHANGE_PASSWORD:
                key = LoginConstant.CHANGE_PASSWORD_CODE_CACHE_PREFIX + target;
                break;
            case LoginConstant.CODE_TYPE_BIND_PHONE:
                key = LoginConstant.BIND_PHONE_CODE_CACHE_PREFIX + target;
                break;
            default:
                return false;
        }
        String cachedCode = stringRedisTemplate.opsForValue().get(key);
        if (cachedCode != null) {
            cachedCode = cachedCode.replaceAll("[^0-9]", ""); // 只留数字
            if (cachedCode.length() > 6) {
                cachedCode = cachedCode.substring(cachedCode.length() - 6); // 取最后6位
            }
        }
        if (code != null) {
            code = code.replaceAll("[^0-9]", "");
        }
        log.info("【验证码校验】Redis存储的验证码：{}，用户输入的验证码：{}", cachedCode, code);
        boolean isValid = code != null && code.equals(cachedCode);
        if (isValid) {
            // 验证成功后删除验证码，防止重复使用
            stringRedisTemplate.delete(key);
        }
        return isValid;
    }

    private String sendAndSaveCode(CodeDTO codeDTO) {
        String code = RandomUtil.randomNumbers(LoginConstant.VERIFY_CODE_LENGTH);
        int type = codeDTO.getType();
        String email = codeDTO.getEmail();

        String key;
        switch (type) {
            case LoginConstant.CODE_TYPE_REGISTER:
                key = LoginConstant.REGISTER_CODE_CACHE_PREFIX + email;
                break;
            case LoginConstant.CODE_TYPE_REST_PASSWORD:
                key = LoginConstant.RESET_PASSWORD_CODE_CACHE_PREFIX + email;
                break;
            case LoginConstant.CODE_TYPE_CHANGE_PASSWORD:
                key = LoginConstant.CHANGE_PASSWORD_CODE_CACHE_PREFIX + email;
                break;
            default:
                return null;
        }

        stringRedisTemplate.opsForValue().set(key, code, 300L);
        emailService.sendCode(email, code);
        return code;
    }
    // 修改用户信息
    @Transactional
    @Override
    public Result<?> updateUserInfo(Long userId, UpdateUserInfoDTO updateUserInfoDTO) {
        //检查用户是否存在
        User user=loginDAO.selectUserById(userId);
        if(user==null){
            return Result.failure(ErrorConstant.USER_NOT_EXIST);
        }
        String oldName= user.getNickname();
        String newName=updateUserInfoDTO.getNickname();
        if (newName != null && !newName.equals(oldName)) {
            int count = loginDAO.checkUsernameExists(newName);
            if (count > 0) {
                return Result.failure("昵称已存在");
            }
        }
        //如果修改手机号，检查新手机号是否已被其他用户占用
        if(updateUserInfoDTO.getPhone()!=null&&!updateUserInfoDTO.getPhone().equals(user.getPhone())){
            User existUser=loginDAO.selectUserByPhone(updateUserInfoDTO.getPhone());
            if(existUser!=null && !existUser.getId().equals(userId)){
                return Result.failure(ErrorConstant.PHONE_ALREADY_EXIST);
            }
        }
        //如果修改邮箱，检查是否已被占用
        if(updateUserInfoDTO.getEmail()!=null&&!updateUserInfoDTO.getEmail().equals(user.getEmail())){
            User existUser=loginDAO.selectUserByEmail(updateUserInfoDTO.getEmail());
            if(existUser!=null && !existUser.getId().equals(userId)){
                return Result.failure(ErrorConstant.EMAIL_ALREADY_EXIST);
            }
        }
        //将更新内容拷贝到用户对象(不覆盖 id、密码等）
        BeanUtils.copyProperties(updateUserInfoDTO,user,"id","password","role","status","createTime","updateTime");
        //更新用户信息
        int rows=loginDAO.updateUserSelective(user);
        if(rows!=1){
            return Result.failure("修改失败，请重试");
        }

        User user1=loginDAO.selectUserById(userId);
        SearchUserInfoDTO searchUserInfoDTO=new SearchUserInfoDTO();
        BeanUtils.copyProperties(user1,searchUserInfoDTO);
        return Result.success(searchUserInfoDTO);
    }

    @Override
    public Result<?> cancelUser(Long userId) {
        //检查用户是否存在且状态正常
        User user=loginDAO.selectUserById(userId);
        if(user==null||user.getStatus()!=1){
            return Result.failure(ErrorConstant.USER_NOT_EXIST);
        }
        // 更新用户状态为取消
        int rows=loginDAO.logout(userId);
        if(rows!=1){
            return Result.failure(ErrorConstant.CANCELED_FAILED);
        }

        //清除 Redis 中的 token（假设存储 key = "token:" + userId）
        String jwt = BaseContext.getCurrentJwt();
        if (jwt != null) {
            stringRedisTemplate.delete(LoginConstant.TOKEN_CACHE_PREFIX + jwt);
        }
        return Result.success("注销成功");
    }
}
