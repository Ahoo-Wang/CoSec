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

"""The planner's decision: which test jobs a pull request's change runs, and that anything it cannot attribute runs all."""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import plan

ROOT = Path(__file__).resolve().parents[2]
RESULT = plan.plan(ROOT)
ALL = [job["name"] for job in RESULT["jobs"]]


def run(*paths):
    jobs, skipped, why = plan.select(RESULT, list(paths))
    return {job["name"] for job in jobs}, {item["job"]: item["reason"] for item in skipped}, why


def projects_of(group):
    return next(job["projects"].split() for job in RESULT["jobs"] if job["name"] == group)


class EverythingRuns(unittest.TestCase):
    def test_a_push_a_schedule_and_a_dispatch_run_every_group(self):
        for event in ("push", "schedule", "workflow_dispatch", "", "merge_group"):
            self.assertIsNone(plan.changed_paths(event, "HEAD^1", "HEAD"), event)
        jobs, skipped, why = plan.select(RESULT, None)
        self.assertEqual(ALL, [job["name"] for job in jobs])
        self.assertEqual([], skipped)
        self.assertIn("not a pull request", why)

    def test_a_change_to_the_build_or_to_ci_runs_every_group(self):
        for path in (
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties", "buildSrc/src/main/kotlin/A.kt", "config/detekt/detekt.yml",
            "codecov.yml", "renovate.json", ".github/workflows/ci.yml", ".github/labeler.yml", "scripts/ci/plan.py",
            "scripts/jmh-ab/compare.py", "bom/dependencies/build.gradle.kts", "bom/bom/build.gradle.kts",
        ):
            ran, skipped, why = run("docs/x.md", path)
            self.assertEqual(set(ALL), ran, path)
            self.assertEqual({}, skipped, path)
            self.assertIn(path, why)

    def test_a_path_the_plan_cannot_attribute_runs_every_group(self):
        for path in (
            ".gitignore", ".editorconfig", "core/newmodule/src/main/kotlin/A.kt", "core/Stray.kt", "unknown/file.txt",
            "Dockerfile", "platform/other/x.kt", "build.gradle.kts.orig",
        ):
            ran, _, why = run("edge/webmvc/src/main/kotlin/A.kt", path)
            self.assertEqual(set(ALL), ran, path)
            self.assertIn(path, why)

    def test_a_pull_request_without_a_readable_diff_runs_every_group(self):
        with tempfile.TemporaryDirectory() as directory:
            git = lambda *args: subprocess.run(["git", *args], cwd=directory, check=True, capture_output=True, text=True)
            git("init", "-q", "-b", "main")
            git("config", "user.email", "t@example.com")
            git("config", "user.name", "t")
            (Path(directory) / "a").write_text("1")
            git("add", "a")
            git("commit", "-q", "-m", "one")
            cwd = os.getcwd()
            os.chdir(directory)
            try:
                # one commit and no second parent: not a pull request's merge checkout
                self.assertIsNone(plan.changed_paths("pull_request", "HEAD^1", "HEAD"))
                git("checkout", "-q", "-b", "topic")
                (Path(directory) / "b").write_text("2")
                git("add", "b")
                git("commit", "-q", "-m", "two")
                git("checkout", "-q", "main")
                (Path(directory) / "c").write_text("3")
                git("add", "c")
                git("commit", "-q", "-m", "three")
                git("merge", "-q", "--no-ff", "-m", "merge", "topic")
                # a merge: what the pull request ("topic") brings, not what main moved by
                self.assertEqual(["b"], plan.changed_paths("pull_request", "HEAD^1", "HEAD"))
                # an empty diff is not a reason to skip: nothing was attributed
                self.assertIsNone(plan.changed_paths("pull_request", "HEAD", "HEAD"))
            finally:
                os.chdir(cwd)


class GroupsFollowTheGraph(unittest.TestCase):
    def test_a_project_runs_its_own_group_and_that_of_every_project_that_depends_on_it(self):
        ran, skipped, _ = run("edge/webmvc/src/main/kotlin/me/ahoo/A.kt")
        self.assertEqual({"edge", "host", "test"}, ran)
        self.assertEqual({"api", "core", "backends", "extensions", "migration"}, set(skipped))

    def test_a_test_source_counts_as_the_project(self):
        self.assertEqual(run("extensions/kafka/src/main/kotlin/A.kt")[0], run("extensions/kafka/src/test/kotlin/A.kt")[0])
        self.assertIn("host", run("extensions/kafka/src/test/kotlin/A.kt")[0])

    def test_the_closure_is_transitive(self):
        # runtime <- control <- cocache (compileOnlyApi) and runtime <- jwt, webflux, webmvc <- spring-boot
        ran, _, _ = run("core/runtime/src/main/kotlin/A.kt")
        self.assertEqual({"core", "backends", "edge", "extensions", "host", "migration", "test"}, ran)

    def test_what_everything_depends_on_runs_every_group(self):
        self.assertEqual(set(ALL), run("api/annotations/src/main/kotlin/A.kt")[0])
        # `api` (annotations, api) depends on nothing of the model, and the model on none of it but annotations
        self.assertEqual(set(ALL) - {"api"}, run("core/model/src/main/kotlin/A.kt")[0])

    def test_a_group_whose_projects_are_all_untouched_is_skipped_with_its_reason(self):
        ran, skipped, why = run("extensions/ip2region/src/main/kotlin/A.kt")
        self.assertEqual({"extensions", "host", "test"}, ran)
        self.assertIsNone(why)
        for reason in skipped.values():
            self.assertIn("none of its projects (", reason)

    def test_a_dependency_declared_in_a_test_configuration_counts(self):
        # spring-boot's tests use kafka through testImplementation: a change to kafka runs them
        self.assertIn("kafka", RESULT["graph"]["spring-boot"])
        self.assertIn("host", run("extensions/kafka/src/main/kotlin/A.kt")[0])

    def test_the_graph_is_the_build_scripts_references(self):
        for name, directory in RESULT["projects"].items():
            script = (ROOT / directory / "build.gradle.kts").read_text()
            expected = set(re.findall(r'project\("?:([a-z0-9-]+)"?\)', script)) - {name}
            self.assertEqual(expected, set(RESULT["graph"][name]), name)


class FilesOutsideTheProjects(unittest.TestCase):
    def test_documentation_runs_the_groups_whose_tests_read_it(self):
        for path in ("wiki/policies/language.md", "wiki/zh/guide/introduction.md", "wiki/examples/app/documents/platform.json"):
            self.assertEqual({"core", "host", "test"}, run(path)[0], path)
        self.assertEqual({"core", "test"}, run("skills/cosec-policy-author/SKILL.md")[0])
        self.assertEqual({"core", "test"}, run("README.md")[0])
        self.assertEqual({"core", "test"}, run("llms.txt")[0])
        self.assertEqual({"host", "test"}, run("k8s/base/application.yaml")[0])
        self.assertEqual({"core", "test"}, run("schema/8.0/policy.schema.json")[0])
        self.assertEqual({"core", "extensions", "test"}, run("contract/token/claims.json")[0])

    def test_what_no_group_reads_runs_only_the_test_group(self):
        for path in ("docs/roadmap.md", "docs/reviews/2026-11/x.md", "typescript/cosec-fetcher/src/a.ts"):
            ran, skipped, _ = run(path)
            self.assertEqual({"test"}, ran, path)
            self.assertEqual(set(ALL) - {"test"}, set(skipped), path)

    def test_conformance_and_architecture_are_projects_of_the_group_test_like_the_others(self):
        for path in (
            "test/conformance/src/test/resources/rules/ACT.json", "test/conformance/mutants/a.patch",
            "test/conformance/src/test/resources/scenarios/ACT/a.json", "test/architecture/src/test/kotlin/A.kt",
            "test/architecture/src/test/kotlin/SourceSizeLedger.kt",
        ):
            ran, skipped, why = run(path)
            self.assertEqual({"test"}, ran, path)
            self.assertIsNone(why)
            self.assertEqual(set(ALL) - {"test"}, set(skipped), path)

    def test_a_json_resource_of_any_project_runs_document_which_checks_every_one(self):
        ran, _, _ = run("edge/gateway/src/test/resources/x.json")
        self.assertEqual({"core", "edge", "host", "test"}, ran)
        # a Kotlin file of the same project does not
        self.assertEqual({"edge", "host", "test"}, run("edge/gateway/src/test/kotlin/A.kt")[0])
        # conformance's own data is skipped by RepositoryDocuments
        self.assertEqual({"test"}, run("test/conformance/src/test/resources/rules/ACT.json")[0])

    def test_a_dependency_of_a_reader_does_not_run_through_a_file_it_does_not_read(self):
        # `document` reads wiki/; `runtime` depends on `document` and reads nothing of it, but is listed on its own
        self.assertNotIn("migration", run("wiki/policies/language.md")[0])
        self.assertNotIn("edge", run("wiki/policies/language.md")[0])

    def test_the_test_group_always_runs_and_a_matrix_is_never_empty(self):
        for paths in (["docs/x.md"], ["typescript/a.ts"], ["extensions/ip2region/src/main/kotlin/A.kt"], ["README.md"]):
            ran, _, _ = run(*paths)
            self.assertIn("test", ran)
            self.assertTrue(set(plan.ALWAYS) <= set(projects_of("test")))


class TheReadsTableHoldsToTheTestSources(unittest.TestCase):
    """A project whose tests name a directory outside every project is in READS for it, or its group never runs on a
    change there. A test group project is covered: the group always runs."""

    LITERAL = re.compile(
        r'"((?:wiki|skills|k8s|schema|contract|docs|typescript)/[^"\n]*|wiki|skills|k8s|contract|typescript|docs)"'
    )
    # What a name in a test source is not a read of: a test that skips the directory, or a temporary file.
    NOT_A_READ = {
        ("document", "docs"),  # RepositoryDocuments skips it (SKIPPED_DIRECTORIES)
    }

    def test_every_project_naming_a_directory_is_listed_for_it(self):
        tracked = subprocess.run(["git", "ls-files", "*/*/src/test/*", "*/*/src/testFixtures/*"], cwd=ROOT, check=True,
                                 capture_output=True, text=True).stdout.split("\n")
        always = set(projects_of("test"))
        missing = []
        for tracked_file in filter(lambda f: f.endswith(".kt"), tracked):
            name = tracked_file.split("/")[1]
            if name in always:
                continue
            for literal in self.LITERAL.findall((ROOT / tracked_file).read_text()):
                directory = literal.split("/")[0]
                if (name, directory) in self.NOT_A_READ:
                    continue
                readers = next((owners for prefix, owners in plan.READS.items() if prefix.rstrip("/") == directory), ())
                if name not in readers:
                    missing.append(f"{name} names `{literal[:60]}` ({tracked_file})")
        self.assertEqual([], sorted(set(missing)), "add the project to READS, or to NOT_A_READ with its reason")

    def test_no_project_outside_the_group_test_depends_on_conformance_or_architecture_or_names_them(self):
        """The narrowing rests on it: a change to either is a change of the group `test` and of nothing else."""
        always = set(projects_of("test"))
        for name, edges in RESULT["graph"].items():
            if name not in always:
                self.assertFalse({"conformance", "architecture"} & set(edges), f"{name} depends on one")
        tracked = subprocess.run(
            ["git", "ls-files", "api", "core", "backends", "edge", "extensions", "host", "migration", "platform"],
            cwd=ROOT, check=True, capture_output=True, text=True).stdout.split("\n")
        pattern = re.compile(r'test/(conformance|architecture)|":(conformance|architecture)"')
        named = []
        for tracked_file in tracked:
            # build scripts and test sources: a comment in a main source names them without reading them
            if not (tracked_file.endswith(".kts") or "/src/test" in tracked_file and tracked_file.endswith((".kt", ".java"))):
                continue
            if pattern.search((ROOT / tracked_file).read_text()) and tracked_file.split("/")[1] not in always:
                named.append(tracked_file)
        # RepositoryDocuments names test/conformance/ to skip it: the one reviewed mention
        self.assertEqual(["core/document/src/test/kotlin/me/ahoo/cosec/document/format/RepositoryDocuments.kt"], named)
        self.assertIn('startsWith("test/conformance/")',
                      (ROOT / named[0]).read_text(), "it skips conformance's data, it does not read it")

    def test_document_still_walks_every_projects_json_resources(self):
        source = (ROOT / "core/document/src/test/kotlin/me/ahoo/cosec/document/format/RepositoryDocuments.kt").read_text()
        self.assertIn("root.walkTopDown()", source)
        self.assertIn('Regex("/src/[^/]+/resources/")', source)

    def test_the_names_in_reads_are_projects(self):
        for prefix, owners in plan.READS.items():
            self.assertTrue(set(owners) <= set(RESULT["projects"]), prefix)
            self.assertTrue((ROOT / prefix.rstrip("/")).is_dir(), prefix)


class Workflow(unittest.TestCase):
    CI = (ROOT / ".github/workflows/ci.yml").read_text()

    def test_the_plan_reads_the_change_and_the_gate_keeps_failing_on_anything_but_success(self):
        self.assertIn("fetch-depth: 2", self.CI)
        self.assertIn('plan.py --event "$GITHUB_EVENT_NAME"', self.CI)
        gate = self.CI[self.CI.index("  ci:\n"):]
        self.assertIn("needs: [ plan, test,", gate)
        self.assertIn('select(.value.result != "success")', gate)
        self.assertNotIn("skipped", gate.split("run: |")[1].split("echo")[0])

    def test_every_job_that_uploads_carries_its_report_forward(self):
        codecov = (ROOT / "codecov.yml").read_text()
        for job in RESULT["jobs"]:
            if job["coverage"]:
                self.assertIn(f"  {job['name']}:\n    carryforward: true", codecov)
        self.assertIsNone(re.search(r"^\s*after_n_builds:", codecov, re.M))
        self.assertIn("    manual_trigger: true\n", codecov)

    def test_the_notifications_are_sent_once_after_every_upload_whatever_their_result(self):
        job = self.CI[self.CI.index("  codecov-notify:\n"):self.CI.index("  ci:\n")]
        self.assertIn("needs: [ test, typescript-coverage ]", job)
        self.assertIn("if: always()", job)
        self.assertIn("run_command: send-notifications", job)


class Output(unittest.TestCase):
    def test_the_workflow_gets_the_jobs_to_run_and_the_skipped_with_reasons(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "output"
            summary = Path(directory) / "summary"
            done = subprocess.run(
                ["python3", "-I", str(ROOT / "scripts/ci/plan.py"), "--root", str(ROOT), "--github-output", str(out),
                 "--changed", "extensions/ip2region/src/main/kotlin/A.kt"],
                capture_output=True, text=True, env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary)})
            self.assertEqual(0, done.returncode, done.stderr)
            fields = dict(line.split("=", 1) for line in out.read_text().splitlines())
            self.assertEqual({"extensions", "host", "test"}, {job["name"] for job in json.loads(fields["matrix"])["include"]})
            self.assertEqual({"api", "core", "backends", "edge", "migration"}, {item["job"] for item in json.loads(fields["skipped"])})
            self.assertIn("skipped `api`", summary.read_text())
            self.assertIn("::notice title=Test api skipped::", done.stderr)


if __name__ == "__main__":
    unittest.main()
