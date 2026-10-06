package com.uav.lowaltitude.modules.identity.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时按当前单位层级和区域目录重建“本单位 / 本单位及下级单位”账号的授权元组（ZT-14）。
 * 管理接口改单位、区域时已经同步重建；这里兜住迁移或其他入口新增的单位与区域，排在各初始化器之后。
 */
@Component
@Order(140)
public class UserDataScopeReconciler implements ApplicationRunner {

    private final UserDataScopeService dataScopes;

    public UserDataScopeReconciler(UserDataScopeService dataScopes) {
        this.dataScopes = dataScopes;
    }

    @Override
    public void run(ApplicationArguments args) {
        dataScopes.refreshAll();
    }
}
