package com.uav.lowaltitude.modules.directory.api;

import java.util.List;
import jakarta.validation.constraints.*;

/** 单位、联系人与登录账号相互独立；通知摘要只包含脱敏联系方式。 */
public final class DirectoryDtos {
 private DirectoryDtos() { }
 public record Page<T>(List<T> items,int page,int size,long total) { }
 public record Organization(String orgId,String orgCode,String name,String parentId,String parentName,String organizationType,
  String address,String creditCode,String remarks,String responsibilities,long version,long createdAt,long updatedAt,long contactCount,long planCount) { }
 public record OrganizationInput(@Size(max=64) String orgCode,@NotBlank @Size(max=128) String name,String parentId,
  @Pattern(regexp="REGULATOR|OPERATOR|SERVICE|OTHER") String organizationType,@Size(max=500) String address,
  @Size(max=64) String creditCode,@Size(max=1000) String remarks,@Size(max=1000) String responsibilities,@Min(0) Long expectedVersion) { }
 public record Contact(String contactId,String orgId,String orgName,String name,List<String> roles,String phone,String email,
  String userId,String userName,boolean enabled,Long validUntil,Long verifiedAt,String verificationBasis,long version,
  long notificationCount,long pendingCount,long historyCount) { }
 public record ContactInput(@NotBlank String orgId,@NotBlank @Size(max=128) String name,@NotEmpty List<String> roles,
  @Size(max=64) String phone,@Email @Size(max=256) String email,String userId,@NotNull Boolean enabled,
  @Min(0) Long validUntil,@Min(0) Long verifiedAt,@Size(max=1000) String verificationBasis,@Min(0) Long expectedVersion) { }
 public record SourceBinding(String bindingId,String sourceId,String sourceName,String externalOrgCode,String orgId,
  String orgName,boolean enabled,long version) { }
 public record BindingInput(@NotBlank String sourceId,@NotBlank @Size(max=128) String externalOrgCode,
  @NotBlank String orgId,@NotNull Boolean enabled,@Min(0) Long expectedVersion) { }
 public record Option(String id,String label,String orgId,String sourceId) { }
 public record Subjects(String planId,String planNo,String sourceId,String sourceName,String sourceBindingId,
  String reportingOrgId,String reportingOrgName,String operatorOrgId,String operatorOrgName,String pilotContactId,
  String pilotName,String pilotContactHint,String associationStatus,long version,RecipientSnapshot feedbackRecipient) { }
 public record SubjectInput(String sourceBindingId,String operatorOrgId,String pilotContactId,
  @NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=1000) String reason) { }
 public record NotificationSetting(String settingId,String purpose,String recipientOrgId,String contactId,
  String sourceBindingId,String recipientId,String recipientName,String orgName,String contactName,String contactHint,
  String sourceName,String channelType,String endpointRef,boolean enabled,Long validUntil,long version,
  String availability,String blockedReason,long historyCount,String templateCode,int templateVersion,String receiptRequirement) { }
 public record NotificationInput(@NotBlank @Pattern(regexp="RISK_NOTICE|PLAN_FEEDBACK|ADVISORY_SMS|ADVISORY_VOICE|UAV_PUNISHMENT|DEVICE_MAINTENANCE") String purpose,
  String recipientOrgId,String contactId,String sourceBindingId,
  @NotBlank @Pattern(regexp="NONE|MOCK|API|SMS|VOICE") String channelType,@Size(max=256) String endpointRef,
  @NotNull Boolean enabled,@Min(0) Long validUntil,@Min(0) Long expectedVersion) { }
 public record Diagnostics(String settingId,String availability,String blockedReason,long historyCount,long pendingCount,long affectedPlanCount) { }
 public record RecipientSnapshot(String recipientId,String recipientName,String orgId,String orgName,String contactId,
  String contactName,String contactHint,String channelType,String endpointRef,String settingId,Long configVersion,
  boolean configured,String blockedReason,Long capturedAt,String templateCode,Integer templateVersion,String receiptRequirement,Long contactVersion) {
  public RecipientSnapshot(String recipientId,String recipientName,String orgId,String orgName,String contactId,String contactName,String contactHint,
   String channelType,String endpointRef,String settingId,Long configVersion,boolean configured,String blockedReason,Long capturedAt) {
   this(recipientId,recipientName,orgId,orgName,contactId,contactName,contactHint,channelType,endpointRef,settingId,configVersion,configured,blockedReason,capturedAt,null,null,null,null);
  }
 }
}
