package com.uav.lowaltitude.modules.device.application;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.platform.api.ApiException;

/** Retired catalog categories remain readable in historical protocol evidence only. */
public final class DeviceCatalogTypes {
    private static final Set<String> RETIRED = Set.of("aoa", "bsc", "dcd", "dec", "ifr", "rid");

    private DeviceCatalogTypes() { }

    public static void requireRegistrable(String code) {
        if (code != null && RETIRED.contains(code.trim().toLowerCase(Locale.ROOT)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEVICE_TYPE_RETIRED", "该设备类型已停用，不能登记或重新启用");
    }
}
