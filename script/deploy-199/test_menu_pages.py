import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('audit-menu-pages.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class MenuPagesTest(unittest.TestCase):
    def row(self, id, component=None, parent=0, type=2):
        return dict(id=id, component=component, parentId=parent, type=type, status=0, visible=1)

    def test_vue_suffix_and_external_page_are_not_missing(self):
        rows = [self.row(1, 'erp/home/index.vue'), self.row(2, '/erp/home/index'),
                self.row(3, 'https://example.invalid/page')]
        self.assertEqual(audit.audit(rows, {'erp/home/index.vue'}), ([], []))

    def test_only_newly_empty_ancestors_are_downlisted(self):
        rows = [self.row(1, type=1), self.row(2, parent=1, type=1), self.row(3, 'cms/missing', 2),
                self.row(4, type=1), self.row(5, 'erp/home', 4), self.row(6, type=1)]
        missing, folders = audit.audit(rows, {'erp/home.vue'})
        self.assertEqual([r['id'] for r in missing], [3])
        self.assertEqual({r['id'] for r in folders}, {1, 2})

    def test_stale_unreachable_bundle_cannot_make_missing_page_available(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'js').mkdir()
            (root / 'index.html').write_text('<script src="/js/main.js"></script>')
            (root / 'js/main.js').write_text('import "./router.js"')
            (root / 'js/router.js').write_text('{"../views/erp/home.vue":()=>null}')
            (root / 'js/old.js').write_text('{"../views/cms/missing.vue":()=>null}')
            self.assertEqual(audit.deployed_views(root), {'erp/home.vue'})

    def test_unknown_asset_layout_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'index.html').write_text('<html/>')
            with self.assertRaises(ValueError):
                audit.deployed_views(root)

    def test_restore_preserves_original_hidden_state_and_guards_edits(self):
        row = self.row(7, "cms/a'b")
        row['visible'] = 0
        sql = audit.sql([row], restore=True)
        self.assertIn('SET status=0,visible=0', sql)
        self.assertIn('AND status=1 AND visible=0', sql)
        self.assertNotIn("a'b", sql)
        self.assertNotIn('DELETE', sql)


if __name__ == '__main__':
    unittest.main()
