package org.example.fruitpickingrobt.vo.login;

import lombok.Data;
import org.example.fruitpickingrobt.constant.LoginConstant;

@Data
public class LoginVO {
    Long id;
    String email;
    String nickname;
    Integer role;
    String token;
    String tokenExpire = LoginConstant.TOKEN_EXPIRE_TIME;
}
