#!/usr/bin/env python3
"""Build evidence-only input mappings and success conditions from an exported OpenAPI.
Never contacts services or reads/modifies KnowDo. UTF-8 JSON or .json.gz output.
"""
import argparse
import copy
import gzip
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

METHODS = {'get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace'}


def read_document(path):
    data = gzip.open(path, 'rb').read() if str(path).endswith('.gz') else Path(path).read_bytes()
    return json.loads(data), hashlib.sha256(data).hexdigest()


def pointer_escape(value):
    return str(value).replace('~', '~0').replace('/', '~1')


class Evidence:
    def __init__(self, document):
        self.document = document

    def lookup(self, pointer):
        if not pointer.startswith('#/'):
            raise ValueError('外部引用未解析：' + pointer)
        result = self.document
        for part in pointer[2:].split('/'):
            key = part.replace('~1', '/').replace('~0', '~')
            result = result[int(key)] if isinstance(result, list) else result[key]
        return result

    def resolve(self, schema, stack=()):
        if not isinstance(schema, dict):
            return {}, ['Schema 不是对象']
        result = copy.deepcopy(schema)
        issues = []
        if '$ref' in result:
            ref = result.pop('$ref')
            if ref in stack:
                return {'x-recursive-reference': ref}, ['递归引用需要按业务限制深度：' + ref]
            try:
                base, found = self.resolve(self.lookup(ref), (*stack, ref))
                issues += found
                # OpenAPI 3.1 siblings apply alongside the referenced schema.
                result, found = self.merge(base, result)
                issues += found
            except (KeyError, ValueError, IndexError):
                return result, ['无法解析引用：' + ref]
        for child in result.pop('allOf', []):
            base, found = self.resolve(child, stack)
            issues += found
            result, found = self.merge(result, base)
            issues += found
        return result, issues

    def merge(self, left, right):
        result = copy.deepcopy(left)
        issues = []
        for key, value in right.items():
            if key == 'required':
                result[key] = sorted(set(result.get(key, [])) | set(value))
            elif key == 'properties':
                for name, prop in value.items():
                    props = result.setdefault(key, {})
                    if name in props:
                        props[name] = {'allOf': [props[name], copy.deepcopy(prop)]}
                    else:
                        props[name] = copy.deepcopy(prop)
            elif key not in result or result[key] == value:
                result[key] = copy.deepcopy(value)
            elif key in ('description', 'title', 'example', 'examples') or key.startswith('x-'):
                # Metadata does not override validation constraints.
                result[key] = value
            elif key == 'type':
                a, b = result[key], value
                common = set(a if isinstance(a, list) else [a]) & set(b if isinstance(b, list) else [b])
                if not common:
                    issues.append('allOf 的 type 冲突，需人工核验')
                else:
                    result[key] = sorted(common)[0] if len(common) == 1 else sorted(common)
            elif key in ('minimum', 'exclusiveMinimum', 'minLength', 'minItems', 'minProperties'):
                result[key] = max(result[key], value)
            elif key in ('maximum', 'exclusiveMaximum', 'maxLength', 'maxItems', 'maxProperties'):
                result[key] = min(result[key], value)
            elif key == 'enum':
                result[key] = [x for x in result[key] if x in value]
                if not result[key]:
                    issues.append('allOf 的 enum 无交集')
            else:
                issues.append('allOf 中存在未自动合并的约束：' + key)
        return result, issues

    def types(self, schema):
        result, _ = self.resolve(schema)
        types = result.get('type', [])
        types = [types] if isinstance(types, str) else list(types)
        if not types:
            if 'properties' in result:
                types = ['object']
            elif 'items' in result:
                types = ['array']
        for kind in ('oneOf', 'anyOf'):
            for branch in result.get(kind, []):
                types += self.types(branch)
        return sorted(set(types))

    def child_pointers(self, schema, pointer, name, visited=()):
        if not isinstance(schema, dict):
            return []
        found = []
        if name in schema.get('properties', {}):
            found.append(pointer + '/properties/' + pointer_escape(name))
        ref = schema.get('$ref')
        if ref and ref not in visited:
            try:
                found += self.child_pointers(self.lookup(ref), ref, name, (*visited, ref))
            except (ValueError, KeyError, IndexError):
                pass
        for index, child in enumerate(schema.get('allOf', [])):
            found += self.child_pointers(child, pointer + '/allOf/' + str(index), name, visited)
        return found

    def fields(self, schema, location, media, pointer, path, required, rows, derived, issues, ancestors=(), origins=None):
        ref = schema.get('$ref') if isinstance(schema, dict) else None
        if ref in ancestors:
            issues.append(f'{location}:{path} 递归对象，映射只展开到首次循环')
            return
        if len(ancestors) > 32:
            issues.append(f'{location}:{path} 展开超过 32 层，需人工核验')
            return
        resolved, found = self.resolve(schema)
        issues.extend(f'{location}:{path} {x}' for x in found)
        schema_pointer = ref or pointer
        row = {'source': {'in': location, 'fieldPath': path, 'schemaPointer': schema_pointer, 'constraintPointers': origins or [schema_pointer]},
               'target': {'in': location, 'fieldPath': path}, 'types': self.types(resolved),
               'requiredWhenParentPresent': bool(required)}
        if media:
            row['target']['mediaType'] = media
        for key in ('description', 'format', 'enum', 'default', 'minimum', 'maximum', 'exclusiveMinimum',
                    'exclusiveMaximum', 'minItems', 'maxItems', 'minLength', 'maxLength', 'pattern'):
            if key in resolved:
                row[key] = resolved[key]
        if resolved.get('readOnly'):
            row['reason'] = 'OpenAPI readOnly：不生成请求输入映射；以 description 中的后端来源约定为准'
            derived.append(row)
            return
        if not row['types']:
            issues.append(f'{location}:{path} 未声明可确定的输入类型')
        if path != '$' and not path.endswith('[]') and not resolved.get('description'):
            issues.append(f'{location}:{path} 缺少字段业务说明')
        if 'properties' in resolved or 'items' in resolved:
            row['structureOnly'] = True
        rows.append(row)
        next_stack = (*ancestors, ref) if ref else (*ancestors, schema_pointer)
        for name, child in resolved.get('properties', {}).items():
            child_path = name if path == '$' else path + '.' + name
            pointers = []
            for origin in origins or [pointer]:
                try:
                    pointers += self.child_pointers(self.lookup(origin), origin, name)
                except (ValueError, KeyError, IndexError):
                    pass
            pointers = list(dict.fromkeys(pointers)) or [schema_pointer]
            self.fields(child, location, media, pointers[0], child_path,
                        name in resolved.get('required', []), rows, derived, issues, next_stack, pointers)
        if 'items' in resolved:
            self.fields(resolved['items'], location, media, schema_pointer + '/items', path + '[]',
                        False, rows, derived, issues, next_stack)
        if 'additionalProperties' in resolved and resolved['additionalProperties'] is not False and not resolved.get('properties'):
            issues.append(f'{location}:{path} 为动态键值结构，具体业务字段不能从 OpenAPI 确定')
        for kind in ('oneOf', 'anyOf'):
            if not resolved.get('x-wire-java-type') and any('object' in self.types(s) or 'array' in self.types(s) for s in resolved.get(kind, [])):
                issues.append(f'{location}:{path} 存在 {kind} 对象分支，须选择实际分支后映射（未猜测分支）')

    def success(self, operation, issues):
        evidence = operation.get('x-business-success')
        if operation.get('x-business-success-unresolved'):
            issues.append(operation['x-business-success-unresolved'])
            return None
        if not evidence:
            issues.append('没有经过实现确认的业务成功条件；不得从 HTTP 200、data 非空或 true 推断')
            return None
        returned = operation.get('x-java-return-type', '')
        common = returned.startswith('cn.iocoder.yudao.framework.common.pojo.CommonResult<')
        captcha = returned == 'com.anji.captcha.model.common.ResponseModel'
        expected = ('code', 0, 'integer', 'msg') if common else ('repCode', '0000', 'string', 'repMsg') if captcha else None
        if not expected or tuple(evidence.get(k) for k in ('path', 'equals', 'valueType', 'errorPath')) != expected:
            issues.append('成功扩展缺少可信的实际返回类型依据或与约定不符')
            return None
        field, equals, kind, error = expected
        if (kind == 'integer' and type(evidence['equals']) is not int) or (kind == 'string' and type(evidence['equals']) is not str):
            issues.append('成功值类型不正确')
            return None
        candidates = []
        for status, response in operation.get('responses', {}).items():
            for media, content in response.get('content', {}).items():
                if media != 'application/json' and not media.endswith('+json'):
                    continue
                schema, found = self.resolve(content.get('schema', {}))
                prop = schema.get('properties', {}).get(field, {})
                prop, prop_issues = self.resolve(prop)
                if self.types(prop) != [kind] or not prop.get('description') or found or prop_issues:
                    continue
                if prop.get('enum') == [equals]:
                    issues.append('公共成功码被限制成唯一成功值，无法描述业务失败')
                    return None
                error_prop = schema.get('properties', {}).get(error, {})
                if 'string' not in self.types(error_prop):
                    continue
                candidates.append({'response': status, 'mediaType': media, 'schema': content.get('schema'),
                                   'successFieldDescription': prop['description']})
        if not candidates:
            issues.append('OpenAPI JSON 响应中未找到类型、说明和错误字段均一致的成功码依据')
            return None
        return {'condition': {'path': field, 'equals': equals}, 'valueType': kind, 'errorPath': error,
                'meaning': evidence.get('meaning'), 'basis': {'javaReturnType': returned, 'responses': candidates}}


def build(document, sha):
    evidence = Evidence(document)
    operations = {}
    reference_errors = []
    def check_refs(value, pointer='#'):
        if isinstance(value, dict):
            if '$ref' in value:
                try:
                    evidence.lookup(value['$ref'])
                except (ValueError, KeyError, IndexError):
                    reference_errors.append({'pointer': pointer, 'ref': value['$ref']})
            for k, v in value.items():
                check_refs(v, pointer + '/' + pointer_escape(k))
        elif isinstance(value, list):
            for i, v in enumerate(value):
                check_refs(v, pointer + '/' + str(i))
    check_refs(document)
    module_counts = Counter()
    for path, item in sorted(document.get('paths', {}).items()):
        for method, op in sorted(item.items()):
            if method not in METHODS:
                continue
            key = method.upper() + ' ' + path
            pointer = '#/paths/' + pointer_escape(path) + '/' + method
            rows, derived, issues = [], [], list(op.get('x-input-manual-review', []))
            parameters = item.get('parameters', []) + op.get('parameters', [])
            path_names = set()
            for i, parameter in enumerate(parameters):
                param, found = evidence.resolve(parameter)
                issues += found
                location, name = param.get('in'), param.get('name')
                if location not in ('path', 'query', 'header', 'cookie') or not name:
                    issues.append('参数缺少正确的 in/name')
                    continue
                if location == 'path':
                    path_names.add(name)
                    if not param.get('required'):
                        issues.append('路径参数必须 required：' + name)
                schema = dict(param.get('schema', {}))
                if param.get('description'):
                    schema.setdefault('description', param['description'])
                start = len(rows)
                evidence.fields(schema, location, None, pointer + '/parameters/' + str(i) + '/schema', name,
                                param.get('required', False), rows, derived, issues)
                for row in rows[start:]:
                    row['serialization'] = {'style': param.get('style', 'form' if location in ('query', 'cookie') else 'simple'),
                                            'explode': param.get('explode', location in ('query', 'cookie'))}
                if location == 'query' and ('object' in evidence.types(schema) or schema.get('format') == 'binary'):
                    issues.append(name + '：query 对象/文件需要核验实际绑定规则')
            if path_names != set(re.findall(r'\{([^}]+)\}', path)):
                issues.append('路径占位符与 path 参数不一致')
            if '*' in path:
                issues.append('路径含 Spring 通配符；需提供真实剩余路径规则，不能直接作为 OpenAPI URL 模板')
            body, found = evidence.resolve(op.get('requestBody', {}))
            issues += found
            for media, content in body.get('content', {}).items():
                evidence.fields(content.get('schema', {}), 'requestBody', media,
                                pointer + '/requestBody/content/' + pointer_escape(media) + '/schema', '$',
                                body.get('required', False), rows, derived, issues)
                if media == '*/*':
                    issues.append('请求体媒体类型为 */*；需确认实际 JSON/文本/回调协议')
            if not op.get('operationId'):
                issues.append('缺少 operationId')
            if not op.get('summary'):
                issues.append('缺少接口业务摘要，需结合对应模块实现核实用途')
            success = evidence.success(op, issues)
            handler = op.get('x-java-handler')
            if not handler:
                issues.append('未记录实际 Java Handler，第三方接口需另查实现')
            entry = {'operationId': op.get('operationId'), 'summary': op.get('summary'),
                     'openapiPointer': pointer, 'javaHandler': handler, 'javaHandlerSignature': op.get('x-java-handler-signature'),
                     'parameterMappings': rows, 'derivedFields': derived,
                     'responseContracts': [
                         {'status': status, 'mediaType': media, 'types': evidence.types(content.get('schema', {})),
                          'schemaPointer': pointer + '/responses/' + status + '/content/' + pointer_escape(media) + '/schema'}
                         for status, response in op.get('responses', {}).items()
                         for media, content in response.get('content', {}).items()],
                     'successCondition': success['condition'] if success else None,
                     'successValueType': success['valueType'] if success else None,
                     'errorMessagePath': success['errorPath'] if success else None,
                     'successMeaning': success['meaning'] if success else None,
                     'basis': success['basis'] if success else {'javaReturnType': op.get('x-java-return-type')},
                     'manualReview': sorted(set(issues))}
            operations[key] = entry
            module = path.split('/')[2] if path.startswith(('/admin-api/', '/app-api/')) else 'third-party'
            module_counts[module] += 1
    reasons = Counter(reason for op in operations.values() for reason in op['manualReview'])
    return {'formatVersion': 1, 'source': {'openapiVersion': document.get('openapi'), 'apiVersion': document.get('info', {}).get('version'),
                'sha256': sha, 'context': 'isolated-source-export-with-production-Jackson-and-springdoc-config',
                'deployed': False, 'knowdoApplied': False},
            'mappingConvention': {'source': 'OpenAPI input structure, not KnowDo tool parameter names',
                'root': '$', 'arrayElement': '[]', 'requiredWhenParentPresent': '只表示直接父对象存在时的必填；必须同时满足祖先 required 与分支约束',
                'structureOnly': '结构节点，不与叶节点重复提交', 'references': 'allOf 为交集；保留 JSON Schema 原始指针作为完整约束依据'},
            'summary': {'operations': len(operations), 'modules': dict(sorted(module_counts.items())),
                'withConfirmedSuccess': sum(o['successCondition'] is not None for o in operations.values()),
                'requiringManualReview': sum(bool(o['manualReview']) for o in operations.values()),
                'referenceErrors': reference_errors, 'manualReasonCounts': dict(reasons.most_common())},
            'operations': operations}


def matches_success(payload, condition):
    """Reference evaluator: JSON value AND type must match. HTTP status/data truthiness are not criteria."""
    if not condition:
        return False
    current = payload
    for part in condition['path'].split('.'):
        if not isinstance(current, dict) or part not in current:
            return False
        current = current[part]
    expected = condition['equals']
    return type(current) is type(expected) and current == expected


def write_document(path, value):
    data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    if str(path).endswith('.gz'):
        Path(path).write_bytes(gzip.compress(data, mtime=0))
    else:
        Path(path).write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('openapi')
    parser.add_argument('output')
    parser.add_argument('--summary')
    args = parser.parse_args()
    document, sha = read_document(args.openapi)
    report = build(document, sha)
    write_document(args.output, report)
    if args.summary:
        write_document(args.summary, {'source': report['source'], **report['summary']})
    print(json.dumps({k: v for k, v in report['summary'].items() if k != 'manualReasonCounts'}, ensure_ascii=False))
    if report['summary']['referenceErrors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
