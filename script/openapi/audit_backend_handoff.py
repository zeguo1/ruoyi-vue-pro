#!/usr/bin/env python3
"""Verify v5 backend handoff contracts against an actual OpenAPI export; no business requests."""
import argparse,json,pathlib,hashlib
p=argparse.ArgumentParser();p.add_argument('document');p.add_argument('--output',required=True);a=p.parse_args()
raw=pathlib.Path(a.document).read_bytes();doc=json.loads(raw);schemas=doc['components']['schemas'];base=pathlib.Path(__file__).parent
errors=[]
def resolve(s):
    if '$ref' in s:
        target=resolve(schemas[s['$ref'].split('/')[-1]])
        return {**target,**{k:v for k,v in s.items() if k!='$ref'}}
    if 'allOf' in s:
        out={'properties':{},'required':[]}
        for part in s['allOf']:
            part=resolve(part);out['properties'].update(part.get('properties',{}));out['required']+=part.get('required',[])
        return out
    return s
ops={f'{m.upper()} {p}':o for p,item in doc['paths'].items() for m,o in item.items() if m in ['get','post','put','patch','delete','head','options','trace']}
def body(op):return resolve(op.get('requestBody',{}).get('content',{}).get('application/json',{}).get('schema',{}))
checked=[]
for row in json.loads((base/'v5-update-contracts.json').read_text()):
    key=row['method'].upper()+' '+row['path'];op=ops.get(key)
    if op is None:errors.append(key+' missing');continue
    s=body(op)
    if 'id' not in s.get('required',[]):errors.append(key+' update id not required')
    expected='string' if '/bpm/model/' in row['path'] else 'integer'
    if s.get('properties',{}).get('id',{}).get('type')!=expected:errors.append(key+' id type mismatch')
    checked.append(key)
# Scan every update request that exposes an id, including the previous v4 fixes.
all_update_ids=[]
for key,op in ops.items():
    path=key.split(' ',1)[1]
    if not path.startswith(('/admin-api/','/app-api/','/rpc-api/')):continue
    if 'update' not in path.rsplit('/',1)[-1]:continue
    s=body(op)
    if 'id' not in s.get('properties',{}):continue
    if path=='/admin-api/hrm/salary/employee-info/update':continue
    if 'id' not in s.get('required',[]):errors.append(key+' unexplained optional update id')
    all_update_ids.append(key)
json_ops=[];other=[]
for key,op in ops.items():
    for status,response in op.get('responses',{}).items():
        for media,value in response.get('content',{}).items():
            ref=value.get('schema',{}).get('$ref','')
            if 'CommonResult' in ref:
                if media=='*/*':errors.append(key+' CommonResult response still wildcard')
                if media=='application/json':json_ops.append(key)
            elif media!='application/json':other.append({'operation':key,'status':status,'mediaType':media})
for kind,field in [('role-menu','menuIds'),('user-role','roleIds')]:
    key='POST /admin-api/system/permission/assign-'+kind;s=body(ops[key]);prop=s['properties'][field]
    if field in s.get('required',[]):errors.append(key+' clear list incorrectly required')
    if not all(word in prop.get('description','') for word in ['全量','清空','null']):errors.append(key+' missing replacement semantics')
    typ=prop.get('type',[])
    if not isinstance(typ,list) or 'null' not in typ:errors.append(key+' explicit null not represented in schema')
for stage,required in [('get',{'captchaType'}),('check',{'captchaType','token','pointJson'})]:
    key='POST /admin-api/system/captcha/'+stage;s=body(ops[key])
    if set(s.get('required',[]))!=required:errors.append(key+' wrong required fields')
    if any(k in s.get('properties',{}) for k in ['captchaVerification','secretKey','captchaId','originalImageBase64']):errors.append(key+' other-stage/output fields leaked')
    if 'application/json' not in ops[key]['responses']['200']['content']:errors.append(key+' JSON response missing')
for resource in ['dept','dict-data','dict-type']:
    key='POST /admin-api/system/'+resource+'/create';s=body(ops[key])
    if 'id' in s.get('required',[]):errors.append(key+' create id required')
    if '传入值忽略' not in s['properties']['id']['description']:errors.append(key+' create id source unclear')
s=body(ops['PUT /admin-api/hrm/salary/employee-info/update'])
if 'id' in s.get('required',[]):errors.append('Salary upsert was incorrectly tightened')
report={'version':doc.get('info',{}).get('version'),'sha256':hashlib.sha256(raw).hexdigest(),'operationCount':len(ops),'newUpdateContractsChecked':len(checked),'allUpdateIdOperationsChecked':len(all_update_ids),'commonResultJsonOperations':len(set(json_ops)),'otherResponseMedia':other,'errors':errors,'checkedUpdateOperations':checked}
pathlib.Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['otherResponseMedia','checkedUpdateOperations']},ensure_ascii=False,indent=2))
raise SystemExit(bool(errors))
