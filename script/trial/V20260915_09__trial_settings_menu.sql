-- Add the control-plane menu only; permission assignment is an explicit per-operator deployment step.
SET NAMES utf8mb4;
START TRANSACTION;
SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE;
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,component,component_name,status,visible,keep_alive,always_show)
SELECT '知办对接配置','crm:trial-settings:query',2,95,1,'knowdo-integration','crm/trial/settings/index','CrmTrialSettings',0,1,0,1
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial-settings:query' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT '保存知办配置','crm:trial-settings:update',3,1,id,'',0,0,0,0 FROM system_menu
WHERE permission='crm:trial-settings:query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial-settings:update' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT '管理知办服务凭据','crm:trial-settings:secret',3,2,id,'',0,0,0,0 FROM system_menu
WHERE permission='crm:trial-settings:query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial-settings:secret' AND deleted=0);
COMMIT;
