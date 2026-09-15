-- Add menus without granting them to existing users/roles. Apply after 01 with the normal MGS migration process.
SET NAMES utf8mb4;
START TRANSACTION;
SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE;
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,component,component_name,status,visible,keep_alive,always_show)
SELECT '试用申请','crm:trial:query',2,90,0,'/trial-operations','crm/trial/operations/index','CrmTrialOperations',0,1,0,1
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial:query' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT '恢复试用申请','crm:trial:recover',3,1,id,'',0,0,0,0 FROM system_menu
WHERE permission='crm:trial:query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial:recover' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,component,component_name,status,visible,keep_alive,always_show)
SELECT '我的业务体验','crm:trial-business:query',2,91,0,'/trial-experience','crm/trial/experience/index','CrmTrialExperience',0,1,0,1
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial-business:query' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show)
SELECT 'Agent 新增本人演示跟进','crm:trial-business:follow-up',3,1,id,'',0,0,0,0 FROM system_menu
WHERE permission='crm:trial-business:query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='crm:trial-business:follow-up' AND deleted=0);
COMMIT;
-- Only an authorized internal operator may assign crm:trial:* to 栖云运营角色.
-- MgsTrialProvisioner assigns exactly the two crm:trial-business permissions to each ordinary trial role.
