-- Repair only the four known mojibake values from the 2026-09-15 deployment.
-- Preserve any independently edited menu labels and all role assignments.
SET NAMES utf8mb4;
START TRANSACTION;
UPDATE system_menu SET name='试用申请' WHERE permission='crm:trial:query' AND deleted=0 AND HEX(name)='C3A8C2AFE280A2C3A7E2809DC2A8C3A7E2809DC2B3C3A8C2AFC2B7';
UPDATE system_menu SET name='恢复试用申请' WHERE permission='crm:trial:recover' AND deleted=0 AND HEX(name)='C3A6C281C2A2C3A5C2A4C28DC3A8C2AFE280A2C3A7E2809DC2A8C3A7E2809DC2B3C3A8C2AFC2B7';
UPDATE system_menu SET name='我的业务体验' WHERE permission='crm:trial-business:query' AND deleted=0 AND HEX(name)='C3A6CB86E28098C3A7C5A1E2809EC3A4C2B8C5A1C3A5C5A0C2A1C3A4C2BDE2809CC3A9C2AAC592';
UPDATE system_menu SET name='Agent 新增本人演示跟进' WHERE permission='crm:trial-business:follow-up' AND deleted=0 AND HEX(name)='4167656E7420C3A6E28093C2B0C3A5C2A2C5BEC3A6C593C2ACC3A4C2BAC2BAC3A6C2BCE2809DC3A7C2A4C2BAC3A8C2B7C5B8C3A8C2BFE280BA';
COMMIT;
