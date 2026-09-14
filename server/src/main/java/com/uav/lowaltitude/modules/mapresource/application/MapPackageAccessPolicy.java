package com.uav.lowaltitude.modules.mapresource.application;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

@Component
public class MapPackageAccessPolicy {
    private final AccessService access;

    public MapPackageAccessPolicy(AccessService access) {
        this.access = access;
    }

    public void requireRead() { access.require(PermissionCode.MAP_READ.value()); }
    public void requireUpload() { access.require(PermissionCode.MAP_UPLOAD.value()); }
    public void requireActivate() { access.require(PermissionCode.MAP_ACTIVATE.value()); }
    public void requireDelete() { access.require(PermissionCode.MAP_DELETE.value()); }
}
