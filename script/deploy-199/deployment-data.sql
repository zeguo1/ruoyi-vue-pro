-- Configure this new installation to store uploaded files in its own database.
-- The upstream default points to a demonstration cloud account.
UPDATE infra_file_config SET master = b'0' WHERE deleted = b'0';
UPDATE infra_file_config
SET master = b'1', name = '本机数据库存储',
    config = JSON_SET(config, '$.domain', 'http://192.168.1.199:8088')
WHERE id = 4 AND storage = 1 AND deleted = b'0';
