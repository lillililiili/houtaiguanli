package com.uav.lowaltitude.modules.directory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Explicit authenticated switch of the old expiring QA transport, never recipient data. */
@Service
public class SimulatorNotificationConfiguration {
 private final DirectoryRepository repository; private final AccessService access;
 private final AuditService audit; private final AppClock clock;
 public SimulatorNotificationConfiguration(DirectoryRepository repository,AccessService access,AuditService audit,AppClock clock){
  this.repository=repository;this.access=access;this.audit=audit;this.clock=clock;
 }
 @Transactional public void activate(){
  access.require("notificationSettings.auth");access.require("organizations.auth");
  int changed=repository.activateSimulatorChannels(clock.nowMillis());
  if(changed>0){var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),"notification_simulator_connected","local_interface","receiver","切换既有测试通知通道至独立数据模拟器；配置数="+changed,null);}
 }
}
