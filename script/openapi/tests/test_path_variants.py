import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from verify_integration_contract import applies_to_path


class PathVariantsTest(unittest.TestCase):
    def test_only_optional_path_variable_absent_from_this_variant_is_skipped(self):
        arg = {'in': 'path', 'name': 'type', 'mvcRequired': False}
        self.assertFalse(applies_to_path(arg, '/view/{id}'))
        self.assertTrue(applies_to_path(arg, '/view/{id}/{type}'))
        self.assertTrue(applies_to_path({**arg, 'mvcRequired': True}, '/view/{id}'))
        self.assertTrue(applies_to_path({**arg, 'in': 'query'}, '/view/{id}'))
        self.assertTrue(applies_to_path({'in': 'path', 'name': 'type'}, '/view/{id}'))
