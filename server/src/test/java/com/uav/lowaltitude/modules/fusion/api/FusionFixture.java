package com.uav.lowaltitude.modules.fusion.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** 阶段 8 API 测试共用夹具：只插自己的数据，所有计数断言都按自身归属过滤（阶段 7 种子会污染 test 库）。 */
final class FusionFixture {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-05T12:00:00Z");
    private final JdbcTemplate jdbc;

    FusionFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String id() { return UUID.randomUUID().toString(); }

    void org(String id, String code) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, code);
    }

    void district(String id, String code) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, code);
    }

    void source(String id, String code, String mode, String type) {
        jdbc.update("insert into integration_source (source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,source_type,created_at,updated_at,version)"
                + " values (?,?,?,'RADAR','3.0',true,?,?,?,?,0)", id, code, code, mode, type, T0, T0);
    }

    void target(String id, String no, String objectType, String mode, String org, String district, long version) {
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?)", id, no, objectType, mode, org, district, T0, T0.plusSeconds(30), T0, T0, version);
    }

    void latestState(String targetId, Double longitude, Double latitude, Double fusionConfidence, String unknownFields) {
        String geometry = longitude == null ? null : "SRID=4326;POINT (" + longitude + " " + latitude + ")";
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,speed_mps,classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " values (?,CAST(? AS GEOMETRY),120.5,10.5,0.8,?,?,?,CAST(? AS JSON),?,?,0)",
                targetId, geometry, fusionConfidence, T0.plusSeconds(9), T0.plusSeconds(10), unknownFields, T0, T0);
    }

    String link(String targetId, String sourceId, String sessionKey, String externalId) {
        String id = id();
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                id, targetId, sourceId, sessionKey, externalId, T0);
        return id;
    }

    String rawTrack(String targetId, String linkId, String externalId) {
        String id = id();
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at,layer) values (?,?,?,?,?,?,'RAW')",
                id, targetId, linkId, externalId, T0.plusSeconds(1), T0);
        return id;
    }

    String fusedTrack(String targetId, String configVersion) {
        String id = id();
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at,layer,config_version) values (?,?,NULL,?,?,?,'FUSED',?)",
                id, targetId, "fused:" + targetId + ":" + T0.toInstant().toEpochMilli(), T0.plusSeconds(1), T0, configVersion);
        return id;
    }

    void point(String trackId, long seq, String kind, double longitude, double latitude, String contributing, String positionSourceId, String degradationLevel) {
        jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,created_at,point_kind,position_accuracy_m,contributing,position_source_id,source_switched,degradation_level)"
                + " values (?,?,?,?,?,CAST(? AS GEOMETRY),12.5,?,?,14.20,CAST(? AS JSON),?,false,?)",
                id(), trackId, seq, T0.plusSeconds(seq), T0.plusSeconds(seq), "SRID=4326;POINT (" + longitude + " " + latitude + ")", T0,
                kind, contributing, positionSourceId, degradationLevel);
    }

    void observation(String sourceId, String sourceType, String sessionKey, String externalId, OffsetDateTime observedAt, String mode, String org, String district) {
        jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,quality,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,?,?,?,?,?,?,CAST(? AS GEOMETRY),15.00,CAST('{}' AS JSON),?,?,?,?)",
                id(), sourceId, sourceType, sessionKey, externalId, observedAt, observedAt, "SRID=4326;POINT (118.6 37.4)", mode, org, district, T0);
    }

    void trackStatus(String targetId, String status) {
        jdbc.update("insert into target_track_status (target_id,status,since,confirm_hits,miss_frames,updated_at,version) values (?,?,?,3,0,?,0)", targetId, status, T0, T0);
    }

    void degradation(String targetId, String level, String availableJson, double deficit, boolean determined) {
        jdbc.update("insert into target_degradation (target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at) values (?,?,CAST(? AS JSON),?,?,?,?)",
                targetId, level, availableJson, deficit, determined, T0, T0);
    }

    void selection(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String configVersion, boolean manualOverride) {
        jdbc.update("insert into target_attribute_selection (target_id,position_source_id,class_source_id,identity_source_id,motion_source_id,class_code,class_confidence,selected_at,config_version,manual_class_override,updated_at,version)"
                + " values (?,?,?,?,?, 'UAV',0.90000,?,?,?,?,0)", targetId, positionSourceId, classSourceId, identitySourceId, positionSourceId, T0, configVersion, manualOverride, T0);
    }

    String lineage(String op, String survivorTargetId, String originTargetId, String membersJson, String configVersion, String operatorId) {
        String id = id();
        jdbc.update("insert into target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,operator_id,note,snapshots,created_at)"
                + " values (?,?,?,?,?,CAST(? AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),'stage8-test',?,?,?,'测试血缘',CAST('{}' AS JSON),?)",
                id, op, T0, survivorTargetId, originTargetId, membersJson, configVersion, operatorId == null ? "SYSTEM" : "USER", operatorId, T0);
        return id;
    }

    void alias(String historicalTargetId, String currentTargetId, String lineageId) {
        jdbc.update("insert into target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at) values (?,?,?,?)", historicalTargetId, currentTargetId, lineageId, T0);
    }

    String role(String suffix, String... permissions) {
        String role = "ROLE-FUSION-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        return role;
    }

    String session(String roleCode, String org, String district, String scope) {
        String user = id(), token = id();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",
                user, "fusion-" + user.substring(0, 8), "融合测试", roleCode, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", user, org, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    String userOf(String session) {
        return jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, session);
    }
}
