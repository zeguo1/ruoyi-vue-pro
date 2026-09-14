#!/usr/bin/env python3
"""Audit a real exported OpenAPI. Read-only, standard library; never calls business APIs."""
import argparse, collections, hashlib, json, pathlib, re
HTTP = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}

def audit(doc):
    issues = collections.defaultdict(list)
    schemas = doc.get('components', {}).get('schemas', {})
    operations = []
    def walk(value, path):
        if isinstance(value, dict):
            ref = value.get('$ref')
            if ref and ref.startswith('#/'):
                node = doc
                try:
                    for part in ref[2:].split('/'):
                        node = node[part.replace('~1','/').replace('~0','~')]
                except (KeyError, TypeError): issues['broken_refs'].append({'path':path, 'ref':ref})
            if 'type' in value and 'default' in value and not matches(value['default'], value['type']):
                issues['invalid_defaults'].append({'path':path,'type':value['type'],'default':value['default']})
            for bound in ['exclusiveMinimum','exclusiveMaximum']:
                if isinstance(value.get(bound), bool): issues['invalid_numeric_bounds'].append(path+'/'+bound)
            if 'type' in value and 'example' in value and not matches(value['example'], value['type']):
                issues['invalid_example_types'].append({'path':path,'type':value['type'],'example':value['example']})
            for key, child in value.items(): walk(child, path+'/'+key)
        elif isinstance(value,list):
            for index, child in enumerate(value): walk(child,path+'/'+str(index))
    def matches(v,t):
        if not isinstance(t,(str,list)):return True
        if isinstance(t,list):return any(matches(v,x) for x in t)
        return {'string':isinstance(v,str), 'integer':isinstance(v,int) and not isinstance(v,bool),
                'number':isinstance(v,(int,float)) and not isinstance(v,bool), 'boolean':isinstance(v,bool),
                'object':isinstance(v,dict), 'array':isinstance(v,list), 'null':v is None}.get(t,True)
    walk(doc,'#')
    ids = collections.defaultdict(list)
    for path, item in doc.get('paths',{}).items():
        for method, op in item.items():
            if method not in HTTP:continue
            name = method.upper()+' '+path
            operations.append(name)
            ids[op.get('operationId')].append(name)
            if not op.get('summary'):issues['missing_operation_summaries'].append(name)
            if '**' in path:issues['wildcard_operations'].append(name)
            for p in op.get('parameters',[]):
                if p.get('in')=='path' and p.get('required') is not True:issues['optional_path_parameters'].append(name+' '+p['name'])
    issues['duplicate_operation_ids'] = [v for k,v in ids.items() if k and len(v)>1]
    for name,schema in schemas.items():
        for field,value in schema.get('properties',{}).items():
            if not value.get('description'):
                issues['missing_property_descriptions'].append({'schema':name,'field':field,'ref':value.get('$ref')})
    issues['missing_owned_property_descriptions'] = [r for r in issues['missing_property_descriptions'] if r['schema'].startswith('cn.iocoder.')]
    issues['missing_owned_operation_summaries'] = [r for r in issues['missing_operation_summaries'] if r.split(' ',1)[1].startswith(('/admin-api/', '/app-api/', '/rpc-api/'))]
    return {'version':doc.get('info',{}).get('version'), 'operation_count':len(operations), 'schema_count':len(schemas),
            'property_count':sum(len(s.get('properties',{})) for s in schemas.values()),
            'issue_counts':{k:len(v) for k,v in sorted(issues.items())}, 'issues':dict(issues), 'operations':sorted(operations)}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('document');p.add_argument('--output',required=True);p.add_argument('--strict',action='store_true');p.add_argument('--inventory');a=p.parse_args()
    raw=pathlib.Path(a.document).read_bytes();report=audit(json.loads(raw));report['sha256']=hashlib.sha256(raw).hexdigest()
    if a.inventory:
        inventory=json.loads(pathlib.Path(a.inventory).read_text())['models'];schemas=json.loads(raw)['components']['schemas'];checked=0;bad=[]
        for name,model in inventory.items():
            schema=schemas.get(model['canonicalName'],{})
            for field,metadata in model['fields'].items():
                prop=schema.get('properties',{}).get(field)
                if not prop:continue
                match=re.fullmatch(r'(?:java.util.(?:List|Set|Collection)<)?(cn\.iocoder\.[\w.$]+)>?',metadata['javaType'])
                if not match:continue
                ref=prop.get('items',prop).get('$ref')
                if ref and ref.startswith('#/components/schemas/'):
                    checked+=1;expected=match[1].replace('$','.')
                    if ref.split('/')[-1]!=expected:bad.append({'model':name,'field':field,'expected':expected,'actual':ref})
        report['source_reference_comparisons']=checked
        report['issues']['source_reference_mismatches']=bad
        report['issue_counts']['source_reference_mismatches']=len(bad)
    pathlib.Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('issues','operations')},ensure_ascii=False,indent=2))
    if a.strict and any(report['issue_counts'].get(k,0) for k in ['broken_refs','invalid_defaults','invalid_numeric_bounds','duplicate_operation_ids','optional_path_parameters','invalid_example_types','missing_owned_property_descriptions','missing_owned_operation_summaries','source_reference_mismatches']):raise SystemExit(1)
