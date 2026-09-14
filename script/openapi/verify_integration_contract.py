#!/usr/bin/env python3
"""Verify the actual exported document against reflected bindings and isolated response fixtures."""
import argparse
import json
from pathlib import Path
import re
from jsonschema import Draft202012Validator
from build_integration_checklist import Evidence, METHODS, build, read_document, matches_success


def verify(document, inventory, downloads, examples):
    evidence = Evidence(document)
    report = build(document, 'verification')
    errors = []
    checked = 0
    def check(condition, message):
        if not condition:
            errors.append(message)
    def schema_for(op, response=False):
        content = op.get('responses', {}).get('200', {}).get('content', {}) if response else op.get('requestBody', {}).get('content', {})
        return content.get('application/json', {}).get('schema', {})
    def body(op):
        return evidence.resolve(schema_for(op))[0]
    def validation(schema, value):
        return list(Draft202012Validator({'components': document.get('components', {}), 'allOf': [schema]}).iter_errors(value))
    by_handler = {}
    binding_manual = []
    for path, item in document['paths'].items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            key = method.upper() + ' ' + path
            by_handler.setdefault(op.get('x-java-handler'), []).append((key, op))
            check(op.get('x-java-handler-signature', op.get('x-java-handler')) in inventory, key + ' missing reflected handler evidence')
            for argument in inventory.get(op.get('x-java-handler-signature', op.get('x-java-handler')), []):
                if argument['in'] == 'requestBody':
                    check(bool(op.get('requestBody')), key + ' missing actual @RequestBody')
                    checked += 1
                    continue
                java_type = argument['javaType']
                if 'java.util.Map' in java_type:
                    binding_manual.append({'operation': key, 'argument': argument, 'reason': 'MVC 动态参数 Map，键来自调用时的请求，不能固定映射为 params 字段'})
                    continue
                param = next((p for p in op.get('parameters', []) if p.get('in') == argument['in'] and p['name'] == argument['name']), None)
                target = param.get('schema', {}) if param else None
                required = bool(param.get('required')) if param else None
                if param is None:
                    for media in ('multipart/form-data', 'application/x-www-form-urlencoded'):
                        form = op.get('requestBody', {}).get('content', {}).get(media, {}).get('schema', {})
                        resolved, _ = evidence.resolve(form)
                        if argument['name'] in resolved.get('properties', {}):
                            target = resolved['properties'][argument['name']]
                            required = argument['name'] in resolved.get('required', [])
                check(target is not None, key + ' missing binding ' + argument['name'])
                if target is None:
                    continue
                check(required == argument['required'], key + ' required mismatch ' + argument['name'])
                expected = {'java.lang.Long':'integer','long':'integer','java.lang.Integer':'integer','int':'integer',
                            'java.lang.Boolean':'boolean','boolean':'boolean','java.lang.String':'string',
                            'java.math.BigDecimal':'number','java.lang.Double':'number','double':'number',
                            'org.springframework.web.multipart.MultipartFile':'string'}.get(java_type)
                if java_type.endswith('[]') or java_type.startswith(('java.util.List<','java.util.Set<','java.util.Collection<')):
                    expected = 'array'
                if expected:
                    check(evidence.types(target) == [expected], key + ' type mismatch ' + argument['name'] + ': ' + str(evidence.types(target)))
                if 'MultipartFile' in java_type:
                    check(param is None and target.get('format') == 'binary', key + ' file must be binary multipart field')
                checked += 1
            for parameter in op.get('parameters', []):
                if 'example' in parameter:
                    check(not validation(parameter.get('schema', {}), parameter['example']), key + ' invalid parameter example ' + parameter['name'])
            for response in op.get('responses', {}).values():
                for content in response.get('content', {}).values():
                    for name, example in content.get('examples', {}).items():
                        if 'value' in example:
                            check(not validation(content.get('schema', {}), example['value']), key + ' invalid response example ' + name)
    # Every file declaration is backed by the actual writer inspected in its source method.
    for download in downloads:
        for key, op in by_handler.get(download['handler'], []):
            media = op['responses']['200'].get('content', {}).get(download['mediaType'], {})
            check(media.get('schema', {}).get('format') == 'binary', key + ' missing download media/binary schema')
            check(report['operations'][key]['successCondition'] is None, key + ' download incorrectly treated as code=0')
    order_create = document['paths']['/admin-api/erp/sale-order/create']['post']
    order_update = document['paths']['/admin-api/erp/sale-order/update']['put']
    for op in (order_create, order_update):
        schema = body(op)
        items = schema['properties']['items']
        item, _ = evidence.resolve(items['items'])
        check({'productId','productUnitId','count','productPrice'} <= item['properties'].keys(), 'sales item lost business fields')
        check('sort' not in item['properties'], 'sales item references unrelated sort model')
        check({'productId','count','productPrice'} <= set(item['required']), 'sales item missing required fields')
        check(item['properties']['productUnitId'].get('readOnly') is True and 'productUnitId' not in item['required'], 'product unit must be derived')
        check('items' in schema['required'] and items.get('minItems') == 1, 'empty order must be rejected')
        valid = {'customerId':101,'orderTime':'2026-09-14T10:20:30','items':[{'productId':201,'count':2.5,'productPrice':100}]}
        if op is order_update:
            valid['id'] = 901
        check(not validation(schema_for(op), valid), 'valid isolated request rejected by actual OpenAPI')
        wrong = {**valid, 'items':[{'id':1,'sort':1}]}
        check(bool(validation(schema_for(op), wrong)), 'actual OpenAPI accepts wrong id/sort-only item')
    check('id' not in body(order_create).get('required', []), 'create ID must not be required')
    check('id' in body(order_update).get('required', []), 'update ID must be required')
    page = document['paths']['/admin-api/system/user/page']['get']['parameters']
    for name, default in [('pageNo',1),('pageSize',10)]:
        field=next(p for p in page if p['name']==name)
        check(not field.get('required') and field['schema'].get('default')==default, name + ' defaults do not match DTO')
    dates=next(p for p in page if p['name']=='createTime')['schema']['items']
    check(dates.get('type')=='string' and dates.get('format') is None and 'yyyy-MM-dd HH:mm:ss' in dates.get('description',''), 'MVC date query does not match actual binding format')
    for calendar in ('/admin-api/hrm/portal/home/calendar','/admin-api/hrm/home/team-calendar','/admin-api/hrm/home/hr-calendar'):
        for parameter in document['paths'][calendar]['get']['parameters']:
            if parameter['name'] in ('startDate','endDate'):
                check('yyyy-MM-dd' in parameter['schema'].get('description',''), calendar + ' ISO date format missing')
    check(not any(p['name']=='createTime' for p in document['paths']['/admin-api/crm/product-category/list']['get']['parameters']),
          'CRM ignored createTime must not be advertised as an effective query filter')
    long_schema=document['components']['schemas']['cn.iocoder.yudao.framework.common.pojo.CommonResultJava.lang.Long']
    check(evidence.types(long_schema['properties']['data'])==['integer','null','string'], 'CommonResult<Long> actual wire union/nullable missing')
    dept_get = document['paths']['/admin-api/system/dept/get']['get']
    dept_list = document['paths']['/admin-api/system/dept/list']['get']
    for op, name in [(dept_get,'nullResult'), (dept_list,'emptyList'), (dept_get,'businessFailure')]:
        check(not validation(schema_for(op, True), examples[name]), 'actual response does not match OpenAPI: ' + name)
        condition=report['operations'][('GET /admin-api/system/dept/get' if op is dept_get else 'GET /admin-api/system/dept/list')]['successCondition']
        check(matches_success(examples[name],condition)==(name!='businessFailure'), 'business response misclassified: '+name)
    # Verify every mapping points to existing original evidence (including allOf and $ref siblings).
    for key, entry in report['operations'].items():
        for row in entry['parameterMappings'] + entry['derivedFields']:
            for pointer in [row['source']['schemaPointer'], *row['source']['constraintPointers']]:
                try:
                    evidence.lookup(pointer)
                except (KeyError, ValueError, IndexError):
                    errors.append(key + ' invalid evidence pointer ' + pointer)
    return {'operations': report['summary']['operations'], 'bindingsChecked':checked,
            'documentedDownloads':len(downloads), 'bindingManualReview':binding_manual,
            'referenceErrors':report['summary']['referenceErrors'], 'errors':errors}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--document',required=True);parser.add_argument('--inventory',required=True)
    parser.add_argument('--downloads',required=True);parser.add_argument('--examples',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args()
    doc,_=read_document(args.document)
    result=verify(doc, json.loads(Path(args.inventory).read_text()), json.loads(Path(args.downloads).read_text()), json.loads(Path(args.examples).read_text()))
    Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('errors','bindingManualReview')},ensure_ascii=False), 'errors:',len(result['errors']))
    if result['errors'] or result['referenceErrors']:
        print('\n'.join(result['errors'][:30]));raise SystemExit(1)

if __name__=='__main__':main()
