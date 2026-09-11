-- Run mysql from the repository root. Import the base schema first on a NEW database.
-- SOURCE paths are relative to the mysql client's working directory.
SOURCE sql/mysql/modules/ai.sql;
SOURCE sql/mysql/modules/bpm.sql;
SOURCE sql/mysql/modules/crm.sql;
SOURCE sql/mysql/modules/erp.sql;
SOURCE sql/mysql/modules/fms.sql;
SOURCE sql/mysql/modules/hrm.sql;
SOURCE sql/mysql/modules/im.sql;
SOURCE sql/mysql/modules/iot.sql;
SOURCE sql/mysql/modules/mall.sql;
SOURCE sql/mysql/modules/member.sql;
SOURCE sql/mysql/modules/mes.sql;
SOURCE sql/mysql/modules/mp.sql;
SOURCE sql/mysql/modules/pay.sql;
SOURCE sql/mysql/modules/pms.sql;
SOURCE sql/mysql/modules/report.sql;
SOURCE sql/mysql/modules/wms.sql;
SOURCE sql/mysql/third-party/jimureport-schema.sql;
