-- 本机回放 MQTT 连接改名：去掉名称里的厂家字样，只改显示名称，连接参数、设备绑定和会话不变。
UPDATE mqtt_broker SET name = 'local-sim-replay' WHERE name = 'local-sim-replay';
