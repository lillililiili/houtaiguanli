package com.uav.lowaltitude.modules.disposal.application;

import org.springframework.stereotype.Service;

/**
 * Retired automatic continuation entry points.
 *
 * A countermeasure must not mint or dispatch a second JAMMING authorization. Keep these methods
 * as compatibility no-ops for older callers; historical child records and their receipt/stop
 * handling remain in the read and safety-stop services. Do not restore a retry or dispatch here.
 */
@Service
public class DisposalJammingChain {
    public void scheduleAfterComplete(String parentAuthorizationId) { }

    public void scheduleAfterDeviceOn(String parentAuthorizationId) { }

    public void retryWhileOn(String parentAuthorizationId) { }

    void chain(String parentAuthorizationId) { }
}
