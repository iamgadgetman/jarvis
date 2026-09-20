import unittest
import publish


class VersionRanges(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(publish.parse_version("1.21.11"), (1, 21, 11))
        self.assertEqual(publish.parse_version("26.3"), (26, 3))
        self.assertIsNone(publish.parse_version("26.3-pre1"))
        self.assertIsNone(publish.parse_version("Fabric"))

    def test_range_spans_the_version_jump(self):
        names = ["1.21.10", "1.21.11", "25.1", "26.1", "26.2", "26.3", "26.3-rc1", "Fabric"]
        self.assertEqual(publish.select_versions(names, "1.21.11..26.2"), ["1.21.11", "25.1", "26.1", "26.2"])
        self.assertEqual(publish.select_versions(names, "26.3"), ["26.3"])

    def test_short_names_as_bukkit_lists_them(self):
        names = ["1.20", "1.21", "1.21.11", "26", "26.2", "27"]
        self.assertEqual(publish.select_versions(names, "1.21.11..26.2"), ["1.21", "1.21.11", "26", "26.2"])

    def test_nothing_in_range_says_what_there_is(self):
        with self.assertRaises(SystemExit) as e:
            publish.select_versions(["1.20.1", "1.20.4"], "26.3")
        self.assertIn("1.20.1", str(e.exception))

    def test_dependency_specs(self):
        self.assertEqual(publish.parse_deps(["fabric-api:required", "simple-voice-chat:optional"], publish.MODRINTH_DEP_KINDS),
                         [("fabric-api", "required"), ("simple-voice-chat", "optional")])
        with self.assertRaises(SystemExit):
            publish.parse_deps(["fabric-api:requiredDependency"], publish.MODRINTH_DEP_KINDS)


if __name__ == "__main__":
    unittest.main()
