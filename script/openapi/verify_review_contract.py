#!/usr/bin/env python3
"""Audit every exported operation; retain SDK/dynamic-input limits instead of inventing contracts."""
import argparse
import json
from collections import Counter
from pathlib import Path
from build_integration_checklist import Evidence, METHODS, read_document


def verify(document):
    evidence = Evidence(document)
    errors, external_missing, non_json, no_parameters, unresolved = [], [], [], [], []
    modules = Counter()
    common_result_count = 0

    def check(ok, message):
        if not ok:
            errors.append(message)

    for path, item in document['paths'].items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            key = method.upper() + ' ' + path
            own = op.get('x-java-handler', '').startswith('cn.iocoder.')
            modules[path.split('/')[2] if path.startswith(('/admin-api/', '/app-api/')) else 'third-party'] += 1
            params = item.get('parameters', []) + op.get('parameters', [])
            for parameter in params:
                param, _ = evidence.resolve(parameter)
                schema, _ = evidence.resolve(param.get('schema', {}))
                if not (param.get('description') or schema.get('description')):
                    row = {'operation': key, 'parameter': param.get('name'), 'in': param.get('in'),
                           'reason': 'SDK 参数未声明业务说明，需要对应 SDK 实现或供应商文档，不能推断业务含义'}
                    if own:
                        errors.append(key + ' 参数缺少说明: ' + str(param.get('name')))
                    else:
                        external_missing.append(row)
            if not params and not op.get('requestBody'):
                no_parameters.append({'operation': key, 'handler': op.get('x-java-handler'),
                                      'basis': 'OpenAPI 未声明业务输入；空映射本身不构成错误，动态 Servlet/SDK 参数仍须按实际协议核验'})
            for media in op.get('requestBody', {}).get('content', {}):
                if media != 'application/json':
                    non_json.append({'operation': key, 'mediaType': media,
                                     'reason': '导入器必须保留媒体类型和字段序列化；不能忽略或改装为 JSON'})
            if op.get('x-business-success-unresolved'):
                unresolved.append({'operation': key, 'reason': op['x-business-success-unresolved']})
            if not op.get('x-java-return-type', '').startswith('cn.iocoder.yudao.framework.common.pojo.CommonResult'):
                continue
            common_result_count += 1
            success = op.get('x-business-success', {})
            check(success.get('path') == 'code' and type(success.get('equals')) is int and success['equals'] == 0,
                  key + ' 缺少有类型的 CommonResult 成功依据')
            content = op.get('responses', {}).get('200', {}).get('content', {}).get('application/json', {})
            result, _ = evidence.resolve(content.get('schema', {}))
            code, _ = evidence.resolve(result.get('properties', {}).get('code', {}))
            check(evidence.types(code) == ['integer'] and not code.get('enum') and 'const' not in code,
                  key + ' code 类型或允许失败码的声明错误')
            check('0 表示成功' in code.get('description', '') and '非 0 表示失败' in code.get('description', ''),
                  key + ' 标准响应 Schema 缺少业务成功/失败依据')

    for method, action in [('post', 'create'), ('put', 'update')]:
        op = document['paths']['/admin-api/erp/stock-check/' + action][method]
        schema, _ = evidence.resolve(op['requestBody']['content']['application/json']['schema'])
        check('盘点' in schema.get('description', '') and '出库' not in schema.get('description', ''),
              '库存盘点请求模型不能描述成出库单: ' + action)
        for name in ('id', 'checkTime', 'items'):
            field, _ = evidence.resolve(schema['properties'][name])
            description = field.get('description', '')
            expected = '已有记录编号' if action == 'update' and name == 'id' else '盘点'
            check(expected in description and '出库' not in description, '库存盘点字段描述错误: ' + action + '.' + name)
        detail, _ = evidence.resolve(schema['properties']['items']['items'])
        check('盘点' in detail['properties']['id'].get('description', ''), '盘点明细编号描述错误')
        check(('id' in schema.get('required', [])) == (action == 'update'), '盘点创建/更新 ID 必填性错误')
    config = document['paths']['/admin-api/infra/config/page']['get']
    name = next(p for p in config['parameters'] if p['name'] == 'name')
    check('参数配置名称' in name.get('description', ''), '参数配置名称不应描述成数据源名称')
    channel = document['paths']['/admin-api/system/sms-channel/page']['get']
    status = next(p for p in channel['parameters'] if p['name'] == 'status')
    check('短信渠道状态' in status.get('description', '') and '0 启用，1 停用' in status.get('description', ''),
          '短信渠道状态说明不符')
    warehouse = document['paths']['/admin-api/erp/warehouse/update-default-status']['put']
    query = {p['name']: p for p in warehouse['parameters'] if p.get('in') == 'query'}
    check(set(query) == {'id', 'defaultStatus'}, '仓库默认状态接口不得多出未绑定的 status 参数')
    check(query.get('defaultStatus', {}).get('schema', {}).get('type') == 'boolean'
          and all(p.get('required') for p in query.values()), '仓库默认状态须为必填布尔参数')

    for module in ('sale', 'purchase'):
        key = '/admin-api/erp/' + module + '-statistics/time-summary'
        count = next(p for p in document['paths'][key]['get']['parameters'] if p['name'] == 'count')
        schema = count['schema']
        check(not count.get('required') and schema.get('default') == 6 and schema.get('minimum') == 1
              and schema.get('maximum') == 120, key + ' 月份默认值/边界不符')
    stock = document['paths']['/admin-api/erp/stock/get']['get']
    rules = stock.get('x-parameter-constraints', {})
    check(rules.get('anyOf') == [{'required': ['id']}, {'required': ['productId', 'warehouseId']}]
          and rules.get('precedence') == 'id', '库存条件组合缺少可读取依据')
    check(not any(p.get('required') for p in stock['parameters']), '库存替代定位字段不可全部标为必填')
    token = document['paths']['/admin-api/system/oauth2/check-token']['post']
    form = token['requestBody']['content']['application/x-www-form-urlencoded']['schema']
    check('token' in form['properties'] and 'token' in form['required'], '校验令牌必须保留表单 token')
    empty = document['paths']['/admin-api/erp/product-category/simple-list']['get']
    check(not empty.get('parameters') and not empty.get('requestBody'), '无参数分类查询不应捏造输入')
    check(empty.get('x-business-input', {}).get('kind') == 'none' and '参数映射允许为空' in empty.get('description', ''),
          '无参数查询必须有来自实际 Handler 的说明')
    page = document['paths']['/admin-api/system/notify-message/my-page']['get']
    for name, default, maximum in [('pageNo', 1, None), ('pageSize', 10, 200)]:
        param = next(p for p in page['parameters'] if p['name'] == name)
        check(not param.get('required') and param['schema'].get('default') == default
              and param['schema'].get('minimum') == 1 and param['schema'].get('maximum') == maximum,
              '站内信分页默认值/范围不符: ' + name)
    return {'apiVersion': document['info']['version'], 'operations': sum(modules.values()),
            'modules': dict(sorted(modules.items())), 'commonResultChecked': common_result_count,
            'errors': errors, 'externalParametersWithoutBusinessDescription': external_missing,
            'nonJsonRequestBodies': non_json, 'operationsWithoutDeclaredBusinessInputs': no_parameters,
            'successSemanticsRequiringManualReview': unresolved}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('document'); parser.add_argument('output')
    args = parser.parse_args()
    document, sha = read_document(args.document)
    result = {'sha256': sha, **verify(document)}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: len(v) if isinstance(v, list) else v for k, v in result.items()}, ensure_ascii=False))
    if result['errors']:
        raise SystemExit('\n'.join(result['errors'][:30]))
