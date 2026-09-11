#!/usr/bin/env python3
"""Import and verify reconstructed tables in an isolated, disposable MySQL container."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

from generate import ROOT, OUTPUT


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout


def main():
    jar = ROOT / "yudao-server/target/yudao-server.jar"
    if not jar.is_file():
        raise SystemExit("Run mvn -B -ntp -DskipTests package first.")
    manifest = json.loads((OUTPUT / "manifest.json").read_text())
    container = "mgs-schema-verify-" + uuid.uuid4().hex[:12]
    image = os.environ.get("MYSQL_TEST_IMAGE", "mysql:8.4")
    started = False
    with tempfile.TemporaryDirectory(prefix="mgs-schema-verify-") as temporary:
        try:
            run("docker", "run", "-d", "--rm", "--name", container, "--network", "none",
                "--tmpfs", "/var/lib/mysql:rw,size=1g", "-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                "-e", "MYSQL_DATABASE=schema_check", image,
                "--skip-log-bin", "--innodb-buffer-pool-size=128M")
            started = True
            # Bounded readiness wait, inside the isolated container.
            run("docker", "exec", container, "sh", "-c",
                "for i in $(seq 1 60); do mysql --protocol=TCP -h127.0.0.1 -uroot schema_check "
                "-e 'SELECT 1' >/dev/null 2>&1 && exit 0; sleep 1; done; exit 1")

            def sql(statement):
                return run("docker", "exec", "-i", container, "mysql", "-uroot", "-N", "-B",
                           "--default-character-set=utf8mb4", "schema_check", input=statement).strip()

            sql((ROOT / "sql/mysql/ruoyi-vue-pro.sql").read_text())
            base_users = sql("SELECT COUNT(*) FROM system_users;")
            for module in manifest["modules"]:
                sql((OUTPUT / f"{module}.sql").read_text())
            count = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();")
            expected = len(manifest["existing_entity_tables"]) + manifest["generated_table_count"]
            assert int(count) == expected, (count, expected)
            for table in manifest["tables"]:
                actual = sql("SELECT COLUMN_NAME FROM information_schema.columns "
                             f"WHERE table_schema=DATABASE() AND table_name='{table['name']}' ORDER BY ordinal_position;").splitlines()
                assert actual == table["columns"], table["name"]
            print(f"Imported {manifest['generated_table_count']} new tables; verified {count} total tables.", flush=True)

            # Use runtime MyBatis metadata, not the source generator's model.
            run("javac", "-d", temporary, str(ROOT / "sql/tools/schema/InspectMappings.java"))
            probes = Path(temporary) / "probes.sql"
            output = run("java", "-Xmx512m", f"-Dloader.path={temporary}", "-Dloader.main=InspectMappings",
                         "-cp", str(jar), "org.springframework.boot.loader.launch.PropertiesLauncher",
                         str(ROOT), str(probes))
            probe_sql = probes.read_text()
            assert probe_sql.count("\nSELECT ") == expected, output
            sql(probe_sql)
            print(f"Verified actual MyBatis-Plus SELECT columns for {expected} compiled entities.", flush=True)

            external = ROOT / "sql/mysql/third-party/jimureport-schema.sql"
            external_tables = json.loads((external.parent / "provenance.json").read_text())["tables"]
            sql(external.read_text())
            for name in external_tables:
                sql(f"SELECT * FROM `{name}` LIMIT 0;")
            total = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();")
            assert int(total) == expected + len(external_tables), total
            print(f"Imported {len(external_tables)} JimuReport/JimuBI library tables; {total} total tables.", flush=True)

            sql("INSERT INTO wms_item_brand (name, tenant_id) VALUES ('schema-check', 9001);")
            sql("INSERT INTO ai_chat_message (content, web_search_pages, attachment_urls) "
                "VALUES (REPEAT('芋', 10000), JSON_ARRAY(JSON_OBJECT('title', '测试')), 'a,b');")
            assert sql("SELECT CHAR_LENGTH(content), JSON_VALID(web_search_pages), attachment_urls "
                       "FROM ai_chat_message;") == "10000\t1\ta,b"
            sql("INSERT INTO bpm_process_definition_info (start_user_ids) VALUES ('1,2,3');")
            assert sql("SELECT FIND_IN_SET('2', start_user_ids) FROM bpm_process_definition_info;") == "2"
            assert sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                       "AND table_name='mp_tag' AND column_name='id' AND extra LIKE '%auto_increment%';") == "0"
            assert sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                       "AND table_name='fms_report_template' AND column_name='tenant_id';") == "0"
            # Explicit tenant scoping prevents the same user label leaking between tenants.
            sql("INSERT INTO wms_item_brand (name, tenant_id) VALUES ('other-tenant', 9002);")
            assert sql("SELECT name FROM wms_item_brand WHERE tenant_id=9001 AND deleted=0;") == "schema-check"

            # An actual unique constraint from H2 must reject duplicates, while
            # the generated discriminator must permit repeated soft deletion.
            insert_template = ("INSERT INTO fms_closing_template (account_set_id, preset_code, tenant_id) "
                               "VALUES (42, 'test', 9001);")
            sql(insert_template)
            try:
                sql(insert_template)
                raise AssertionError("FMS active template uniqueness was lost")
            except subprocess.CalledProcessError as error:
                assert "1062" in error.stderr, error.stderr
            sql("UPDATE fms_closing_template SET deleted=1;")
            sql(insert_template)
            sql("UPDATE fms_closing_template SET deleted=1;")
            sql(insert_template)
            assert sql("SELECT COUNT(*) FROM fms_closing_template;") == "3"

            for module in manifest["modules"]:
                sql((OUTPUT / f"{module}.sql").read_text())
            sql(external.read_text())
            assert sql("SELECT COUNT(*) FROM system_users;") == base_users
            assert sql("SELECT name FROM wms_item_brand WHERE tenant_id=9001;") == "schema-check"
            assert sql("SELECT COUNT(*) FROM fms_closing_template;") == "3"
            print("Verified repeat import preserves data, tenant columns, CSV/JSON/long text, INPUT IDs and soft-delete uniqueness.", flush=True)
        finally:
            if started:
                run("docker", "stop", container)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr or error.stdout or str(error))
