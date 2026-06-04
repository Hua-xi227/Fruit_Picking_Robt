package org.example.fruitpickingrobt.dto;

import lombok.Data;

@Data
public class ResetPasswordDTO {
    String email;
    String newPassword;
    String verifyCode;
}