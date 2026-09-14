package com.uav.lowaltitude.modules.mapresource.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(70)
public class MapRuntimeConfigInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MapRuntimeConfigInitializer.class);

    private final MapPackageService maps;

    public MapRuntimeConfigInitializer(MapPackageService maps) {
        this.maps = maps;
    }

    @Override
    public void run(ApplicationArguments args) {
        maps.reconcileRuntimePointer();
        log.info("map runtime pointer reconciled from database state");
    }
}
