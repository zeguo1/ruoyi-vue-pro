#!/usr/bin/env python3
"""Read-only checks for a generated OpenAPI document and an optional pre-fix snapshot.

python3 script/openapi/verify_item_contract.py --document URL_OR_JSON --before OLD_JSON --report report.json
No business endpoints are invoked.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import ProxyHandler, build_opener


def load(source):
    raw = (build_opener(ProxyHandler({})).open(source, timeout=120).read()
           if source.startswith(('http://', 'https://')) else Path(source).read_bytes())
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def refs(value):
    if isinstance(value, dict):
        if '$ref' in value:
            yield value['$ref']
        for child in value.values():
            yield from refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from refs(child)


def dereference(document, ref):
    assert ref.startswith('#/'), ref
    value = document
    for part in ref[2:].split('/'):
        value = value[part.replace('~1', '/').replace('~0', '~')]
    return value


def expand(document, value):
    """Flatten a request model's inheritance without recursively expanding cycles."""
    result = dict(value)
    if '$ref' in result:
        result = {**expand(document, dereference(document, result.pop('$ref'))), **result}
    for parent in result.pop('allOf', []):
        parent = expand(document, parent)
        result['properties'] = {**parent.get('properties', {}), **result.get('properties', {})}
        result['required'] = sorted(set(parent.get('required', [])) | set(result.get('required', [])))
    return result


def affected_operations(document):
    affected = []
    for path, methods in document['paths'].items():
        for method, operation in methods.items():
            if method not in {'get', 'post', 'put', 'patch', 'delete'}:
                continue
            pending = list(refs(operation)); seen = set()
            while pending:
                ref = pending.pop()
                if ref in seen:
                    continue
                seen.add(ref)
                if ref == '#/components/schemas/Item':
                    affected.append({'method': method.upper(), 'path': path, 'summary': operation.get('summary')})
                    break
                pending.extend(refs(dereference(document, ref)))
    return affected


def verify(document):
    # Dangling schema refs and shared anonymous Item refs must both disappear.
    for ref in set(refs(document)):
        assert ref != '#/components/schemas/Item', 'Still references the colliding Item schema'
        if ref.startswith('#/'):
            dereference(document, ref)
    checked = []
    for action, method in [('create', 'post'), ('update', 'put')]:
        path = '/admin-api/erp/sale-order/' + action
        request = document['paths'][path][method]['requestBody']['content']['application/json']['schema']
        order = expand(document, request)
        array = expand(document, order['properties']['items'])
        item = expand(document, array['items'])
        assert {'productId', 'productPrice', 'count', 'taxPercent', 'remark'} <= item['properties'].keys()
        assert set(item['properties']) == {'id', 'productId', 'productUnitId', 'productPrice', 'count', 'taxPercent', 'remark'}
        assert {'productId', 'productPrice', 'count'} <= set(item['required'])
        assert 'productUnitId' not in item['required']
        for field in ('count', 'productPrice'):
            bound = item['properties'][field].get('exclusiveMinimum')
            assert type(bound) in (int, float) and bound == 0, (path, field, 'must document > 0')
        assert item['properties']['productId']['minimum'] == 1
        assert item['properties']['productUnitId']['readOnly'] is True
        assert {'customerId', 'orderTime', 'items'} <= set(order['required'])
        assert ('id' in order['required']) == (action == 'update')
        assert 'discountPercent' not in order['required']
        assert array.get('minItems') == 1
        checked.append({'method': method.upper(), 'path': path, 'itemRef': array['items'].get('$ref'),
                        'required': item['required'], 'fields': sorted(item['properties'])})
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--document', required=True)
    parser.add_argument('--before')
    parser.add_argument('--models', help='Generated yudao-server/target/item-model-contracts.json')
    parser.add_argument('--report')
    args = parser.parse_args()
    doc, digest = load(args.document)
    report = {'source': args.document, 'sha256': digest, 'version': doc['info']['version'],
              'salesOrderContracts': verify(doc), 'remainingSharedItemOperations': affected_operations(doc)}
    if args.before:
        before, previous_digest = load(args.before)
        report['beforeSha256'] = previous_digest
        report['previouslyAffectedOperations'] = affected_operations(before)
        report['previousSharedItemFields'] = sorted(before['components']['schemas']['Item']['properties'])
    if args.models:
        found, absent = [], []
        for ref, fields in json.loads(Path(args.models).read_text()).items():
            if ref.rsplit('/', 1)[-1] not in doc['components']['schemas']:
                absent.append(ref)
                continue
            actual = expand(doc, {'$ref': ref})
            assert set(fields) == set(actual.get('properties', {})), ref
            found.append(ref)
        assert len(found) >= 35, f'Only {len(found)} Item models verified in actual export'
        report['verifiedItemModels'] = found
        report['modelsNotExposedAsComponents'] = absent
    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        Path(args.report).write_text(output + '\n')
    print(output)


if __name__ == '__main__':
    main()
