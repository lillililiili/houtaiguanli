-- 四通道网络控制器设置命令：与 device_command 一对一，不走凌云 MQTT 控制表。
CREATE TABLE countermeasure_4ch_command (
    command_id        VARCHAR(36) PRIMARY KEY REFERENCES device_command (command_id),
    action            VARCHAR(16) NOT NULL,
    channel           VARCHAR(8),
    mask              INTEGER,
    authorization_id  VARCHAR(64) NOT NULL,
    CONSTRAINT ck_cm4ch_action CHECK (action IN ('CHANNEL_ON', 'CHANNEL_OFF', 'SET_MASK')),
    CONSTRAINT ck_cm4ch_channel CHECK (channel IS NULL OR channel IN ('900M', '1.5G', '2.4G', '5.8G')),
    CONSTRAINT ck_cm4ch_pair CHECK (
        (action IN ('CHANNEL_ON', 'CHANNEL_OFF') AND channel IS NOT NULL AND mask IN (1, 2, 4, 8))
        OR (action = 'SET_MASK' AND channel IS NULL AND mask IN (0, 13, 15))
    )
);
