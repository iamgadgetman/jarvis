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

class CurseForgeVersions(unittest.TestCase):
    TYPES = [{"id": 1, "slug": "bukkit"}, {"id": 3, "slug": ""}, {"id": 615, "slug": "addons"},
             {"id": 77784, "slug": "minecraft-1-21"}, {"id": 88556, "slug": "minecraft-26-3"},
             {"id": 68441, "slug": "modloader"}, {"id": 2, "slug": "java"}, {"id": 75208, "slug": "environment"}]
    VERSIONS = [
        {"id": 900, "gameVersionTypeID": 615, "name": "1.21.11"},   # Addons, listed first
        {"id": 901, "gameVersionTypeID": 1, "name": "1.21.11"},     # Bukkit
        {"id": 902, "gameVersionTypeID": 77784, "name": "1.21.11"}, # Minecraft
        {"id": 903, "gameVersionTypeID": 88556, "name": "26.3"},
        {"id": 904, "gameVersionTypeID": 1, "name": "26.3"},
        {"id": 905, "gameVersionTypeID": 3, "name": "2.0.0.65"},    # another game's number
        {"id": 906, "gameVersionTypeID": 68441, "name": "Fabric"},
        {"id": 907, "gameVersionTypeID": 2, "name": "Java 25"},
        {"id": 908, "gameVersionTypeID": 75208, "name": "Server"},
    ]

    def test_bukkit_takes_bukkit_versions_only(self):
        chosen, ids = publish.curseforge_version_ids("dev.bukkit.org", self.TYPES, self.VERSIONS, "1.21.11..26.2", [])
        self.assertEqual((chosen, ids), (["1.21.11"], [901]))

    def test_mods_take_minecraft_versions_and_the_extras(self):
        chosen, ids = publish.curseforge_version_ids("minecraft.curseforge.com", self.TYPES, self.VERSIONS,
                                                     "26.3", ["Fabric", "Java 25", "Server"])
        self.assertEqual((chosen, ids), (["26.3"], [903, 906, 907, 908]))

    def test_other_games_numbers_are_not_in_range(self):
        _, ids = publish.curseforge_version_ids("minecraft.curseforge.com", self.TYPES, self.VERSIONS, "1.21.11..26.3", [])
        self.assertNotIn(905, ids)
        self.assertEqual(ids, [902, 903])

    def test_unknown_extra_names_what_there_is(self):
        with self.assertRaises(SystemExit) as e:
            publish.curseforge_version_ids("minecraft.curseforge.com", self.TYPES, self.VERSIONS, "26.3", ["Java 99"])
        self.assertIn("Java 25", str(e.exception))


if __name__ == "__main__":
    unittest.main()
