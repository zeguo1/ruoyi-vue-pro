import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("connector_key", Path(__file__).with_name("create_connector_key.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ConnectorKeyTest(unittest.TestCase):
    def create(self, target, scopes=("TOOLS",)):
        return module.create(target, "tools-test", "knowdo-test", scopes, ["assistant-test"], ["anonymous"], ["web"])

    def test_private_files_and_hash_without_installing_or_enabling(self):
        with tempfile.TemporaryDirectory() as root:
            target = self.create(Path(root) / "key")
            token = (target / "service-token.txt").read_text().strip()
            data = (target / "mgs-config.json").read_text()
            config = json.loads(data)["mgs"]["trial"]["connector"]
            self.assertFalse(config["enabled"])
            key = config["keys"]["tools-test"]
            self.assertFalse(key["enabled"])
            self.assertEqual(key["token-sha256"], hashlib.sha256(token.encode()).hexdigest())
            self.assertNotIn(token, data)
            self.assertRegex(token, r"^mgs_trial\.tools-test\.[A-Za-z0-9_-]{43}$")
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)
            for file in target.iterdir():
                self.assertEqual(stat.S_IMODE(file.stat().st_mode), 0o600)

    def test_no_overwrite(self):
        with tempfile.TemporaryDirectory() as root:
            target = self.create(Path(root) / "key")
            before = (target / "service-token.txt").read_bytes()
            with self.assertRaises(FileExistsError):
                self.create(target)
            self.assertEqual(before, (target / "service-token.txt").read_bytes())

    def test_mixed_or_unknown_scopes_rejected_without_creating_files(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / "key"
            for scopes in [("TOOLS", "CONSENT"), ("ADMIN",), ()]:
                with self.assertRaises(ValueError):
                    self.create(target, scopes)
                self.assertFalse(target.exists())

    def test_private_key_has_no_tool_capability(self):
        with tempfile.TemporaryDirectory() as root:
            target = self.create(Path(root) / "card", ("SMS_VERIFICATION", "CONSENT"))
            config = json.loads((target / "mgs-config.json").read_text())
            self.assertNotIn("TOOLS", config["mgs"]["trial"]["connector"]["keys"]["tools-test"]["capabilities"])

if __name__ == "__main__":
    unittest.main()
