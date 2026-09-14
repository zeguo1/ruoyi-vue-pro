import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('checklist', Path(__file__).parents[1] / 'build_integration_checklist.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class ChecklistTest(unittest.TestCase):
    def document(self):
        result = {'type': 'object', 'properties': {
            'code': {'type': 'integer', 'description': '业务成功为 0，非 0 失败'},
            'msg': {'type': 'string', 'description': '错误说明'},
            'data': {'anyOf': [{'type': 'object'}, {'type': 'null'}]}}}
        return {'openapi': '3.1.0', 'info': {'version': 'test'}, 'components': {'schemas': {
            'Result': result, 'Base': {'type': 'object', 'properties': {'id': {'type': 'integer', 'description': '已有 ID'}}}}},
            'paths': {'/admin-api/demo/{id}': {'put': {
                'operationId': 'update', 'x-java-handler': 'demo#update',
                'x-java-return-type': 'cn.iocoder.yudao.framework.common.pojo.CommonResult<java.lang.Boolean>',
                'x-business-success': {'path': 'code', 'equals': 0, 'valueType': 'integer', 'errorPath': 'msg'},
                'parameters': [{'name': 'id', 'in': 'path', 'required': True, 'schema': {'type': 'integer', 'description': '路径编号'}}],
                'requestBody': {'required': True, 'content': {'application/json': {'schema': {'allOf': [
                    {'$ref': '#/components/schemas/Base'}, {'type': 'object', 'required': ['id'], 'properties': {
                        'items': {'type': 'array', 'description': '明细', 'items': {'type': 'object', 'required': ['count'], 'properties': {
                            'count': {'type': 'number', 'description': '用户确认数量'},
                            'productUnitId': {'type': 'integer', 'readOnly': True, 'description': '取自商品资料'}}}}}}]}}}},
                'responses': {'200': {'content': {'application/json': {'schema': {'$ref': '#/components/schemas/Result'}}}}}}}}}
    def operation(self, doc):
        return doc['paths']['/admin-api/demo/{id}']['put']
    def entry(self, doc):
        return m.build(doc, 'test')['operations']['PUT /admin-api/demo/{id}']
    def test_typed_success_rejects_failure_string_boolean_and_missing(self):
        condition = self.entry(self.document())['successCondition']
        self.assertEqual(condition, {'path':'code', 'equals':0})
        for data in (None, [], False, {}, True):
            self.assertTrue(m.matches_success({'code': 0, 'data': data}, condition))
        for payload in ({'code':400}, {'code':'0'}, {'code':False}, {'code':0.0}, {'data':True}, {}):
            self.assertFalse(m.matches_success(payload, condition))
    def test_allof_nested_and_readonly_preserve_input_locations(self):
        entry = self.entry(self.document())
        mappings = {(r['source']['in'], r['source']['fieldPath']): r for r in entry['parameterMappings']}
        self.assertTrue(mappings[('requestBody','id')]['requiredWhenParentPresent'])
        self.assertEqual(mappings[('requestBody','items[].count')]['types'], ['number'])
        self.assertNotIn(('requestBody','items[].productUnitId'), mappings)
        self.assertEqual(entry['derivedFields'][0]['source']['fieldPath'], 'items[].productUnitId')
        self.assertIn(('path','id'), mappings)
        evidence=m.Evidence(self.document())
        for row in entry['parameterMappings'] + entry['derivedFields']:
            evidence.lookup(row['source']['schemaPointer'])
            for pointer in row['source']['constraintPointers']:
                evidence.lookup(pointer)
    def test_not_every_response_or_oauth_approval_is_success(self):
        for returned in ('void', 'org.springframework.web.servlet.mvc.method.annotation.SseEmitter', 'java.lang.String'):
            doc = self.document(); self.operation(doc)['x-java-return-type'] = returned
            self.assertIsNone(self.entry(doc)['successCondition'])
        doc = self.document(); self.operation(doc)['x-business-success-unresolved'] = 'data 可能含 access_denied'
        self.assertIsNone(self.entry(doc)['successCondition'])
    def test_single_success_enum_missing_ref_or_wrong_code_type_are_not_trusted(self):
        for change in ({'enum':[0]}, {'type':'string'}):
            doc=self.document(); doc['components']['schemas']['Result']['properties']['code'].update(change)
            self.assertIsNone(self.entry(doc)['successCondition'])
        doc=self.document(); del doc['components']['schemas']['Result']
        self.assertTrue(m.build(doc, 'test')['summary']['referenceErrors'])
        self.assertIsNone(self.entry(doc)['successCondition'])
    def test_media_and_form_fields_are_not_guessed_as_json(self):
        doc=self.document(); body=self.operation(doc)['requestBody']['content']; body['multipart/form-data']=body.pop('application/json')
        rows=self.entry(doc)['parameterMappings']
        self.assertTrue(all(r['target']['mediaType']=='multipart/form-data' for r in rows if r['source']['in']=='requestBody'))
    def test_captcha_uses_string_success_code(self):
        doc=self.document(); op=self.operation(doc)
        op['x-java-return-type']='com.anji.captcha.model.common.ResponseModel'
        op['x-business-success']={'path':'repCode','equals':'0000','valueType':'string','errorPath':'repMsg'}
        doc['components']['schemas']['Result']['properties']={'repCode':{'type':'string','description':'成功为 0000'},'repMsg':{'type':'string'}}
        cond=self.entry(doc)['successCondition']
        self.assertEqual(cond, {'path':'repCode','equals':'0000'})
        self.assertFalse(m.matches_success({'repCode':0}, cond))
        self.assertFalse(m.matches_success({'repCode':'0016'}, cond))
    def test_recursive_and_conflicting_models_are_reported(self):
        doc=self.document(); base=doc['components']['schemas']['Base']
        base['properties']['child']={'$ref':'#/components/schemas/Base'}
        self.assertTrue(any('递归' in s for s in self.entry(doc)['manualReview']))
        schema, issues=m.Evidence(doc).resolve({'allOf':[{'type':'string'},{'type':'integer'}]})
        self.assertTrue(issues)

if __name__ == '__main__':
    unittest.main()
