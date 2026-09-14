package com.uav.lowaltitude.modules.identity.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.platform.api.ApiException;

@Component
public class PasswordPolicy {

    public void validateTemporary(String password, String account) {
        check(password, account, "临时密码需为6至32位，并包含大写字母、小写字母、数字和特殊字符，且不能包含登录账号");
    }

    public void validate(String password, String account) {
        check(password, account, "密码需为6至32位，并包含大写字母、小写字母、数字和特殊字符，且不能包含登录账号");
    }

    private static void check(String password, String account, String message) {
        boolean valid = password != null
                && password.length() >= 6
                && password.length() <= 32
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))
                && (account == null || !password.toLowerCase().contains(account.toLowerCase()));
        if (!valid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION", message);
        }
    }
}
