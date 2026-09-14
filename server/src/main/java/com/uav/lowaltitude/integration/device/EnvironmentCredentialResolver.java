package com.uav.lowaltitude.integration.device;

import org.springframework.stereotype.Component;

@Component
public class EnvironmentCredentialResolver {

    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) return null;
        if (!credentialRef.startsWith("env:") || credentialRef.length() <= 4)
            throw new ProtocolException("CREDENTIAL_UNAVAILABLE", "首版 live 凭据仅支持 env:变量名 引用");
        String value = System.getenv(credentialRef.substring(4));
        if (value == null || value.isBlank())
            throw new ProtocolException("CREDENTIAL_UNAVAILABLE", "凭据引用无法解析");
        return value.trim();
    }
}
