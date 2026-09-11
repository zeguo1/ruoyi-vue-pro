-- Run with the TDengine client, NOT MySQL.
-- Matches TDENGINE_URL's default database in application.yaml.
-- Source: yudao-module-iot/yudao-module-iot-biz/src/main/resources/mapper/device/IotDeviceMessageMapper.xml
CREATE DATABASE IF NOT EXISTS ruoyi_vue_pro;
USE ruoyi_vue_pro;

CREATE STABLE IF NOT EXISTS device_message (
    ts TIMESTAMP,
    id NCHAR(50),
    report_time TIMESTAMP,
    tenant_id BIGINT,
    server_id NCHAR(50),
    upstream BOOL,
    reply BOOL,
    identifier NCHAR(100),
    request_id NCHAR(50),
    method NCHAR(100),
    params VARCHAR(8192),
    data VARCHAR(8192),
    code INT,
    msg NCHAR(256)
) TAGS (
    device_id BIGINT
);

-- Product-property supertables and per-device subtables are created dynamically
-- by IotDevicePropertyMapper / IotDeviceMessageMapper from the product model.
