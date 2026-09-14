package com.uav.lowaltitude.integration.mock;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 9 空域演示夹具（双门禁：!production 且 local/test，且 app.dev-seed.enabled=true）。
 * 内容：两片空域（其中一片有两个版本，旧版 valid_to 已被新版接替关闭）与一个 STAGED 导入批次。
 * 只补缺行（WHERE NOT EXISTS）：重启不覆盖任何人工新建的空域、版本或导入决定。
 * 不碰阶段 3/7 的种子空域——那些是既有研判的输入证据。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(85)
public class LocalStage9AirspaceSeeder implements ApplicationRunner {
    public static final String ORG = stableId("demo-org:platform");
    public static final String DISTRICT = stableId("demo-district:dongying");
    public static final String AIRSPACE_SUCCEEDED = "seed-stage9-airspace-succeeded";
    public static final String AIRSPACE_SINGLE = "seed-stage9-airspace-single";
    public static final String VERSION_1 = "seed-stage9-av-succeeded-1", VERSION_2 = "seed-stage9-av-succeeded-2";
    public static final String VERSION_SINGLE = "seed-stage9-av-single-1";
    public static final String BATCH = "seed-stage9-import-batch";
    /** 演示基准时刻：2026-09-05 08:00 北京时间。 */
    public static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

    private final JdbcTemplate jdbc;

    public LocalStage9AirspaceSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureScope();
        String seeder = anySeedUser();
        airspace(AIRSPACE_SUCCEEDED, "KY-9-001", "演示禁飞区（已换版）");
        airspace(AIRSPACE_SINGLE, "KY-9-002", "演示限高区");
        // 第 1 版在第 2 版生效时被接替关闭：页面的版本时间线要能看到"接替"这件事本身。
        version(VERSION_1, AIRSPACE_SUCCEEDED, 1, "PROHIBITED", square(118.50, 37.30), null, null, null,
                T0.minusSeconds(86_400), T0, "首次划设");
        version(VERSION_2, AIRSPACE_SUCCEEDED, 2, "PROHIBITED", square(118.52, 37.31), null, null, null,
                T0, null, "范围东移，接替第 1 版");
        version(VERSION_SINGLE, AIRSPACE_SINGLE, 1, "ALTITUDE_LIMIT", square(118.60, 37.40), 0, 120, "AMSL",
                T0.minusSeconds(86_400), null, "首次划设");
        origin(VERSION_1, "SEED", null, null);
        origin(VERSION_2, "SEED", null, VERSION_1);
        origin(VERSION_SINGLE, "SEED", null, null);
        if (seeder != null) importBatch(seeder);
    }

    private void ensureScope() {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0"
                + " where not exists (select 1 from app_org where org_id=?)", ORG, "DEMO-PLATFORM", "演示平台机构", ORG);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0"
                + " where not exists (select 1 from app_district where district_id=?)", DISTRICT, "DEMO-DONGYING", "东营演示区域", DISTRICT);
    }

    /** 导入批次要有创建者；本地库里取任意一个已有用户，取不到就不种导入批次（不为了种子造账号）。 */
    private String anySeedUser() {
        return jdbc.query("select user_id from app_user order by created_at, user_id fetch first 1 rows only",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    private void airspace(String id, String no, String name) {
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,?,NULL,'live',?,?,?,?,0 where not exists (select 1 from airspace where airspace_id=?)",
                id, no, name, ORG, DISTRICT, ts(T0), ts(T0), id);
    }

    private void version(String id, String airspaceId, int versionNo, String kindCode, String boundary, Integer min, Integer max,
            String datum, Instant validFrom, Instant validTo, String reason) {
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,"
                + "altitude_datum,valid_from,valid_to,change_reason,created_at)"
                + " select ?,?,?,?,CAST(? AS GEOMETRY),?,?,?,?,?,?,? where not exists (select 1 from airspace_version where airspace_version_id=?)",
                id, airspaceId, versionNo, kindCode, boundary, min, max, datum, ts(validFrom), ts(validTo), reason, ts(T0), id);
    }

    private void origin(String versionId, String kind, String actorId, String supersededId) {
        jdbc.update("insert into airspace_version_origin (origin_id,airspace_version_id,origin_kind,actor_id,import_item_id,superseded_version_id,created_at)"
                + " select ?,?,?,?,NULL,?,? where not exists (select 1 from airspace_version_origin where airspace_version_id=?)",
                "seed-stage9-origin-" + versionId.substring(versionId.lastIndexOf('-') + 1) + "-" + versionId.hashCode(),
                versionId, kind, actorId, supersededId, ts(T0), versionId);
    }

    /** 一个待决定的导入批次：一条可接受、一条因缺种类被拒，页面能演示"确认 / 放弃"两条路。 */
    private void importBatch(String createdBy) {
        jdbc.update("insert into airspace_import_batch (batch_id,status,feature_count,accepted_count,note,owner_org_id,district_id,created_by,created_at,version)"
                + " select ?,'STAGED',2,1,?,?,?,?,?,0 where not exists (select 1 from airspace_import_batch where batch_id=?)",
                BATCH, "演示导入批次", ORG, DISTRICT, createdBy, ts(T0), BATCH);
        item("seed-stage9-import-item-1", 1, "导入示例甲区", "KY-9-101", "RESTRICTED", square(118.70, 37.50), "[]", true);
        item("seed-stage9-import-item-2", 2, "导入示例乙区", "KY-9-102", null, null,
                "[{\"field\":\"kind_code\",\"reason_code\":\"KIND_MISSING\"}]", false);
    }

    private void item(String id, int seq, String name, String no, String kindCode, String boundary, String issues, boolean accepted) {
        jdbc.update("insert into airspace_import_item (item_id,batch_id,seq,name,airspace_no,kind_code,boundary_geojson,boundary,"
                + "min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,issues,accepted,created_at)"
                + " select ?,?,?,?,?,?,NULL,CAST(? AS GEOMETRY),NULL,NULL,NULL,?,NULL,CAST(? AS JSON),?,? "
                + " where not exists (select 1 from airspace_import_item where item_id=?)",
                id, BATCH, seq, name, no, kindCode, boundary, ts(T0), issues, accepted, ts(T0), id);
    }

    /** 约 1 km 见方的演示边界，坐标顺序固定 [经度, 纬度]。
     *  经纬度必须落在地图组件的东营视图范围内（约 118.114–119.308 / 36.937–38.156），
     *  否则视图会被钳制到边界，页面上看起来"没画出来"。 */
    private static String square(double longitude, double latitude) {
        double d = 0.01;
        return "SRID=4326;MULTIPOLYGON(((" + longitude + " " + latitude + "," + (longitude + d) + " " + latitude + ","
                + (longitude + d) + " " + (latitude + d) + "," + longitude + " " + (latitude + d) + ","
                + longitude + " " + latitude + ")))";
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static String stableId(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
}
