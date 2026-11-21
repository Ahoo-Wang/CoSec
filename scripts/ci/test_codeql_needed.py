#!/usr/bin/env python3
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""codeql_needed.py: what it skips, and that a new source root or build file cannot fall outside what it analyses."""
import subprocess
import unittest
from pathlib import Path

import codeql_needed as needed

ROOT = Path(__file__).resolve().parents[2]


class DecisionTest(unittest.TestCase):
    def test_a_pull_request_of_documentation_is_skipped(self):
        analyse, reason, hits = needed.decide("pull_request", ["wiki/guide/introduction.md", "docs/roadmap.md", "README.md"])
        self.assertFalse(analyse)
        self.assertEqual("skipped: no Kotlin, Java or build file changed", reason)
        self.assertEqual([], hits)

    def test_what_is_not_compiled_is_skipped(self):
        for path in ("wiki/zh/operations/upgrading.md", "skills/cosec-integration/SKILL.md", "typescript/cosec-fetcher/src/a.ts",
                     "k8s/base/deployment.yaml", ".github/workflows/ci.yml", ".github/scripts/README.md", "scripts/ci/plan.py",
                     "test/conformance/src/test/resources/scenarios/CTL/CTL-1-x.json", "schema/8.0/policy.schema.json",
                     "host/spring-boot/src/main/resources/META-INF/spring/x.imports", "codecov.yml"):
            self.assertFalse(needed.decide("pull_request", [path])[0], path)

    def test_any_kotlin_java_or_build_file_is_analysed(self):
        for path in ("core/engine/src/main/kotlin/A.kt", "core/engine/src/test/kotlin/ATest.kt", "x/y/src/jmh/kotlin/B.kt",
                     "a/b/src/main/java/C.java", "build.gradle.kts", "settings.gradle.kts", "core/model/build.gradle.kts",
                     "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties", "gradle.properties", "buildSrc/x.kt",
                     ".github/codeql/codeql-config.yml", ".github/workflows/codeql.yml", ".github/gradle/resolve-dependencies.init.gradle.kts",
                     ".github/actions/setup-java-gradle/action.yml", ".github/scripts/resolve-dependencies.sh", "scripts/ci/codeql_needed.py"):
            analyse, _, hits = needed.decide("pull_request", ["wiki/a.md", path])
            self.assertTrue(analyse, path)
            self.assertEqual([path], hits)

    def test_a_main_a_schedule_and_a_manual_run_analyse_whatever_changed(self):
        for event in ("push", "schedule", "workflow_dispatch"):
            self.assertTrue(needed.decide(event, ["wiki/a.md"])[0], event)
            self.assertTrue(needed.decide(event, None)[0], event)

    def test_what_cannot_be_read_is_analysed(self):
        self.assertTrue(needed.decide("pull_request", None)[0])

    def test_an_empty_change_is_skipped_only_for_a_pull_request(self):
        self.assertFalse(needed.decide("pull_request", [])[0])
        self.assertTrue(needed.decide("push", [])[0])


class TreeTest(unittest.TestCase):
    """The list is held to the tree: nothing the build compiles may fall outside it."""

    def tracked(self):
        out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
        return out.stdout.splitlines()

    def test_every_kotlin_java_and_gradle_file_of_the_tree_is_analysed(self):
        compiled = [p for p in self.tracked() if p.endswith((".kt", ".kts", ".java", ".gradle"))]
        self.assertGreater(len(compiled), 500)
        self.assertEqual([], [p for p in compiled if not needed.triggers([p])])

    def test_every_build_script_and_the_version_catalog_are_analysed(self):
        build = [p for p in self.tracked() if p.endswith("build.gradle.kts") or p in ("settings.gradle.kts", "gradle/libs.versions.toml")]
        self.assertIn("gradle/libs.versions.toml", build)
        self.assertEqual([], [p for p in build if not needed.triggers([p])])

    def test_no_documentation_is_analysed(self):
        docs = [p for p in self.tracked() if p.startswith(("wiki/", "docs/", "skills/")) and p.endswith(".md")]
        self.assertGreater(len(docs), 50)
        self.assertEqual([], needed.triggers(docs))

    def test_a_source_root_is_not_named_in_the_list(self):
        # A new module, or a new source set, is covered by its extension and not by a path: nothing here names one.
        for fragment in ("core/", "src/main", "src/test", "host/", "edge/"):
            self.assertNotIn(fragment, needed.ANALYSED.pattern)


if __name__ == "__main__":
    unittest.main()
