-- 设备海拔由接入人员分开填写：地面海拔 + 架设高度 = 海拔高度（altitude_m，基准 AMSL）。
-- 两列都可空；以前直接登记的 altitude_m 保持原样。
ALTER TABLE ops_device ADD COLUMN ground_elevation_m DECIMAL(8, 2);
ALTER TABLE ops_device ADD COLUMN mount_height_m DECIMAL(8, 2);
