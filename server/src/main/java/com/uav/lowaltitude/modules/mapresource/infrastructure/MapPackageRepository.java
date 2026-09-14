package com.uav.lowaltitude.modules.mapresource.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MapPackageRepository {
    private final JdbcTemplate jdbc;

    public MapPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PackageRow> list() {
        return jdbc.query("""
                SELECT package_id,package_name,city_code,city_name,data_version,coordinate_system,
                       archive_name,package_path,archive_sha256,manifest_sha256,size_bytes,file_count,
                       bounds_west,bounds_south,bounds_east,bounds_north,min_zoom,max_zoom,display_max_zoom,
                       manifest_json,status,validation_message,uploaded_by,uploaded_by_name,uploaded_at,
                       activated_by,activated_by_name,activated_at,version
                FROM map_package
                WHERE status <> 'DELETED'
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'VALIDATED' THEN 1 ELSE 2 END, uploaded_at DESC
                """, PACKAGE_MAPPER);
    }

    public PackageRow find(String packageId, boolean lock) {
        List<PackageRow> rows = jdbc.query("""
                SELECT package_id,package_name,city_code,city_name,data_version,coordinate_system,
                       archive_name,package_path,archive_sha256,manifest_sha256,size_bytes,file_count,
                       bounds_west,bounds_south,bounds_east,bounds_north,min_zoom,max_zoom,display_max_zoom,
                       manifest_json,status,validation_message,uploaded_by,uploaded_by_name,uploaded_at,
                       activated_by,activated_by_name,activated_at,version
                FROM map_package WHERE package_id=?
                """ + (lock ? " FOR UPDATE" : ""), PACKAGE_MAPPER, packageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findIdByArchiveSha256(String sha256) {
        // 删除只移除地图字节，可审计的元数据仍保留；相同归档因此始终视为已上传。
        List<String> rows = jdbc.query("SELECT package_id FROM map_package WHERE archive_sha256=?",
                (rs, index) -> rs.getString(1), sha256);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(PackageRow row) {
        jdbc.update("""
                INSERT INTO map_package (
                    package_id,package_name,city_code,city_name,data_version,coordinate_system,
                    archive_name,package_path,archive_sha256,manifest_sha256,size_bytes,file_count,
                    bounds_west,bounds_south,bounds_east,bounds_north,min_zoom,max_zoom,display_max_zoom,
                    manifest_json,status,validation_message,uploaded_by,uploaded_by_name,uploaded_at,version
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, row.packageId(), row.packageName(), row.cityCode(), row.cityName(), row.dataVersion(),
                row.coordinateSystem(), row.archiveName(), row.packagePath(), row.archiveSha256(),
                row.manifestSha256(), row.sizeBytes(), row.fileCount(), row.boundsWest(), row.boundsSouth(),
                row.boundsEast(), row.boundsNorth(), row.minZoom(), row.maxZoom(), row.displayMaxZoom(),
                row.manifestJson(), "VALIDATED", row.validationMessage(), row.uploadedBy(),
                row.uploadedByName(), row.uploadedAt());
    }

    public RuntimeRow runtime(boolean lock) {
        return jdbc.queryForObject("""
                SELECT config_id,active_package_id,previous_package_id,revision,updated_by,updated_at,version
                FROM map_runtime_config WHERE config_id='global'
                """ + (lock ? " FOR UPDATE" : ""), RUNTIME_MAPPER);
    }

    public int retire(String packageId) {
        if (packageId == null) return 0;
        return jdbc.update("UPDATE map_package SET status='RETIRED',version=version+1 WHERE package_id=? AND status='ACTIVE'",
                packageId);
    }

    public int activate(String packageId, int expectedPackageVersion, String actorId, String actorName, long at) {
        return jdbc.update("""
                UPDATE map_package
                SET status='ACTIVE',activated_by=?,activated_by_name=?,activated_at=?,version=version+1
                WHERE package_id=? AND version=? AND status IN ('VALIDATED','RETIRED')
                """, actorId, actorName, at, packageId, expectedPackageVersion);
    }

    public int updateRuntime(String activePackageId, String previousPackageId, int expectedVersion,
            long revision, String actorId, long at) {
        return jdbc.update("""
                UPDATE map_runtime_config
                SET active_package_id=?,previous_package_id=?,revision=?,updated_by=?,updated_at=?,version=version+1
                WHERE config_id='global' AND version=?
                """, activePackageId, previousPackageId, revision, actorId, at, expectedVersion);
    }

    public int markDeleted(String packageId, int expectedVersion, String actorId, long at, String reason) {
        return jdbc.update("""
                UPDATE map_package
                SET status='DELETED',deleted_by=?,deleted_at=?,delete_reason=?,version=version+1
                WHERE package_id=? AND version=? AND status IN ('VALIDATED','RETIRED')
                """, actorId, at, reason, packageId, expectedVersion);
    }

    private static final RowMapper<PackageRow> PACKAGE_MAPPER = (rs, index) -> new PackageRow(
            rs.getString("package_id"), rs.getString("package_name"), rs.getString("city_code"),
            rs.getString("city_name"), rs.getString("data_version"), rs.getString("coordinate_system"),
            rs.getString("archive_name"), rs.getString("package_path"), rs.getString("archive_sha256"),
            rs.getString("manifest_sha256"), rs.getLong("size_bytes"), rs.getInt("file_count"),
            decimal(rs, "bounds_west"), decimal(rs, "bounds_south"), decimal(rs, "bounds_east"),
            decimal(rs, "bounds_north"), rs.getInt("min_zoom"), rs.getInt("max_zoom"),
            rs.getInt("display_max_zoom"), rs.getString("manifest_json"), rs.getString("status"),
            rs.getString("validation_message"), rs.getString("uploaded_by"), rs.getString("uploaded_by_name"),
            rs.getLong("uploaded_at"), rs.getString("activated_by"), rs.getString("activated_by_name"),
            nullableLong(rs, "activated_at"), rs.getInt("version"));

    private static final RowMapper<RuntimeRow> RUNTIME_MAPPER = (rs, index) -> new RuntimeRow(
            rs.getString("active_package_id"), rs.getString("previous_package_id"), rs.getLong("revision"),
            rs.getString("updated_by"), nullableLong(rs, "updated_at"), rs.getInt("version"));

    private static double decimal(ResultSet rs, String name) throws SQLException {
        BigDecimal value = rs.getBigDecimal(name);
        return value == null ? Double.NaN : value.doubleValue();
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    public record PackageRow(
            String packageId, String packageName, String cityCode, String cityName, String dataVersion,
            String coordinateSystem, String archiveName, String packagePath, String archiveSha256,
            String manifestSha256, long sizeBytes, int fileCount, double boundsWest, double boundsSouth,
            double boundsEast, double boundsNorth, int minZoom, int maxZoom, int displayMaxZoom,
            String manifestJson, String status, String validationMessage, String uploadedBy,
            String uploadedByName, long uploadedAt, String activatedBy, String activatedByName,
            Long activatedAt, int version) { }

    public record RuntimeRow(String activePackageId, String previousPackageId, long revision,
            String updatedBy, Long updatedAt, int version) { }
}
