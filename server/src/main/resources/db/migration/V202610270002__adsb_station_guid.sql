-- 航班接收机的机身识别码（pingStation3 的 pingStationGuid，16 位十六进制）。
-- 接收机通过 UDP 推送航班数据时带这个识别码，平台据此知道是哪台登记的设备；没登记的识别码一律不收。
ALTER TABLE adsb_receiver ADD COLUMN station_guid VARCHAR(16);
ALTER TABLE adsb_receiver ADD CONSTRAINT uq_adsb_receiver_station_guid UNIQUE (station_guid);
