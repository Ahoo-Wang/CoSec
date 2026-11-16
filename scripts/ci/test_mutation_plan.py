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


"""Tests of scripts/ci/mutation_plan.py: `python3 -I -m unittest discover -s scripts/ci`."""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("mutation_plan", ROOT / "scripts" / "ci" / "mutation_plan.py")
mutation_plan = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mutation_plan)


def repository(mutants, redis="REDIS = {\"spring-boot\", \"cocache\"}"):
    """A temporary repository root holding plan.py's REDIS and the given mutants: {name: (rule, tests)}."""
    root = Path(tempfile.mkdtemp())
    (root / "scripts" / "ci").mkdir(parents=True)
    (root / "scripts" / "ci" / "plan.py").write_text(redis + "\n")
    directory = root / "test" / "conformance" / "mutants"
    directory.mkdir(parents=True)
    for name, (rule, tests) in mutants.items():
        (directory / f"{name}.patch").write_text(
            f"# Rule: {rule}\n# Mutant: breaks it\n# Tests: {tests}\n\ndiff --git a/f b/f\n")
    return root


def failure(call):
    err = io.StringIO()
    with contextlib.redirect_stderr(err), unittest.TestCase().assertRaises(SystemExit):
        call()
    return err.getvalue()


class MutationPlanTest(unittest.TestCase):
    def test_a_shard_is_a_rule_family(self):
        root = repository({
            "alg-1-a": ("ALG-1", ":engine:test --tests X"),
            "alg-2-b": ("ALG-2, ALG-3", ":engine:test"),
            "idn-1-c": ("IDN-1", ":runtime:test"),
        })
        shards = mutation_plan.plan(root)["shards"]
        self.assertEqual(["alg", "idn"], [shard["name"] for shard in shards])
        self.assertEqual("alg-1-a,alg-2-b", shards[0]["mutants"])
        self.assertEqual(3, mutation_plan.plan(root)["mutants"])

    def test_a_family_is_cut_into_chunks(self):
        root = repository({f"idn-{n:02d}-m": ("IDN-1", ":runtime:test") for n in range(7)})
        shards = mutation_plan.plan(root, max_per_shard=3)["shards"]
        self.assertEqual(["idn-1", "idn-2", "idn-3"], [shard["name"] for shard in shards])
        self.assertEqual([3, 2, 2], [shard["count"] for shard in shards])
        every = ",".join(shard["mutants"] for shard in shards).split(",")
        self.assertEqual(sorted(every), every)
        self.assertEqual(7, len(set(every)))

    def test_redis_comes_from_plan_py_and_follows_the_tests_not_the_code(self):
        root = repository({
            "a-1": ("ALG-1", ":engine:test"),
            "s-1": ("SCP-1", ":spring-boot:test --tests X :conformance:test"),
            "c-1": ("CND-1", ":cocache:test"),
        })
        redis = {shard["name"]: shard["redis"] for shard in mutation_plan.plan(root)["shards"]}
        self.assertEqual({"alg": False, "cnd": True, "scp": True}, redis)
        other = repository({"s-1": ("SCP-1", ":spring-boot:test")}, redis="REDIS = {\"e2e\"}")
        self.assertFalse(mutation_plan.plan(other)["shards"][0]["redis"])

    def test_a_subset_keeps_only_the_named_mutants(self):
        root = repository({
            "alg-1-a": ("ALG-1", ":engine:test"),
            "alg-2-b": ("ALG-1", ":engine:test"),
            "idn-1-c": ("IDN-1", ":runtime:test"),
        })
        shards = mutation_plan.plan(root, only={"alg-2-b", "idn-1-c"})["shards"]
        self.assertEqual(["alg-2-b", "idn-1-c"], [shard["mutants"] for shard in shards])

    def test_an_unknown_mutant_or_a_bad_header_fails(self):
        root = repository({"alg-1-a": ("ALG-1", ":engine:test")})
        self.assertIn("no such mutant", failure(lambda: mutation_plan.plan(root, only={"nope"})))
        (root / "test/conformance/mutants/x.patch").write_text("# Mutant: m\n\ndiff --git a/f b/f\n")
        self.assertIn("x.patch has no `# Rule:` line", failure(lambda: mutation_plan.plan(root)))
        bad = repository({"y": ("123", ":engine:test")})
        self.assertIn("rule family", failure(lambda: mutation_plan.plan(bad)))
        empty = repository({})
        self.assertIn("has no patch", failure(lambda: mutation_plan.plan(empty)))

    def test_the_repository_is_planned_whole(self):
        plan = mutation_plan.plan(ROOT)
        patches = sorted(path.stem for path in (ROOT / "test/conformance/mutants").glob("*.patch"))
        planned = sorted(name for shard in plan["shards"] for name in shard["mutants"].split(","))
        self.assertEqual(patches, planned, "every mutant is in exactly one shard")
        self.assertTrue(all(shard["count"] <= mutation_plan.MAX_PER_SHARD for shard in plan["shards"]))
        self.assertEqual(len(patches), plan["mutants"])
        self.assertTrue(any(shard["redis"] for shard in plan["shards"]), "some mutants are tested against Redis")
        self.assertTrue(any(not shard["redis"] for shard in plan["shards"]))
        json.dumps(plan)


if __name__ == "__main__":
    unittest.main()
