import unittest

import javalang

from generate import annotation, annotation_value, column_definition, column_type, persistent_fields, split_sql


class SchemaGeneratorTest(unittest.TestCase):
    def test_excludes_nonpersistent_and_nested_fields_but_keeps_inherited_fields(self):
        tree = javalang.parse.parse('''
            class BaseDO { private Boolean deleted; private String shadow; }
            @TableName(value="example", excludeProperty={"omitted"})
            class Example extends BaseDO {
                @TableId private Long id;
                @TableField(exist=false) private String shadow;
                private static String constant;
                private transient String temporary;
                private String omitted;
                class Nested { private String jsonMember; }
            }
        ''')
        classes = {c.name: (c, None) for c in tree.types}
        self.assertEqual(set(persistent_fields(tree.types[1], classes)), {"id", "deleted"})

    def test_csv_is_not_json_and_json_objects_are_supported(self):
        node = javalang.parse.parse('''
            class Example {
                @TableField(typeHandler=LongListTypeHandler.class) private List<Long> ids;
                @TableField(typeHandler=JacksonTypeHandler.class) private Config config;
            }
        ''').types[0]
        self.assertEqual(column_type(node.fields[0], node.fields[0].declarators[0], ""), "text")
        self.assertEqual(column_type(node.fields[1], node.fields[1].declarators[0], ""), "json")

    def test_input_primary_key_is_not_auto_increment(self):
        f = javalang.parse.parse('class Tag { @TableId(type=IdType.INPUT) private Long id; }').types[0].fields[0]
        _, suffix, primary = column_definition("id", "bigint", f, f.declarators[0], "")
        self.assertTrue(primary)
        self.assertNotIn("AUTO_INCREMENT", suffix)

    def test_preserves_explicit_column_name(self):
        f = javalang.parse.parse('class Example { @TableField("external_key") private String key; }').types[0].fields[0]
        self.assertEqual(annotation_value(annotation(f, "TableField")), "external_key")

    def test_unknown_types_fail_instead_of_silently_losing_columns(self):
        f = javalang.parse.parse('class Example { private CustomValue value; }').types[0].fields[0]
        with self.assertRaisesRegex(ValueError, "Unknown persistent Java type"):
            column_type(f, f.declarators[0], "")

    def test_unbounded_strings_and_h2_large_varchar_do_not_truncate(self):
        f = javalang.parse.parse('class Example { private String content; }').types[0].fields[0]
        for hint in ("", "varchar", "varchar(1000000)"):
            self.assertEqual(column_type(f, f.declarators[0], hint), "longtext")
        self.assertEqual(column_type(f, f.declarators[0], "varchar(128)"), "varchar(128)")

    def test_sql_parser_handles_delimiters_in_strings_and_expressions(self):
        sql = "`a` decimal(24,6), `b` varchar(50) DEFAULT 'a,b', UNIQUE (`a`, `b`)"
        self.assertEqual(len(list(split_sql(sql))), 3)
        self.assertEqual(list(split_sql("SELECT 'a;b'; SELECT 1;", ";")), ["SELECT 'a;b'", "SELECT 1"])


if __name__ == "__main__":
    unittest.main()
