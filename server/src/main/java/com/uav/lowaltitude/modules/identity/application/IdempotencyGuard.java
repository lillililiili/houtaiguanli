package com.uav.lowaltitude.modules.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.identity.infrastructure.IdempotencyMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Component
public class IdempotencyGuard {

    private final IdempotencyMapper mapper;
    private final AppClock appClock;

    public IdempotencyGuard(IdempotencyMapper mapper, AppClock appClock) {
        this.mapper = mapper;
        this.appClock = appClock;
    }

    public void claim(String key, String operation) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key 必须为8至128个字符");
        }
        AuthUser actor = AuthContext.require();
        String storedKey = sha256(actor.userId() + ":" + key.trim());
        String requestHash = sha256(operation);
        String previous = mapper.findHash(storedKey);
        if (previous != null) {
            if (requestHash.equals(previous)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY",
                        "该操作已提交，请刷新页面查看最新结果");
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于其他操作");
        }
        try {
            mapper.insert(storedKey, actor.userId(), requestHash, appClock.nowMillis());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于其他操作");
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
