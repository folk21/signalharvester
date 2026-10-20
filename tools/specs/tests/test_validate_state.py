import copy
import json
import tempfile
import unittest
from pathlib import Path
from importlib.util import module_from_spec, spec_from_file_location

LOCATION = Path(__file__).resolve().parents[1] / "validate_state.py"
spec = spec_from_file_location("validate_state", LOCATION)
module = module_from_spec(spec)
spec.loader.exec_module(module)


class StateContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        path = self.repo / "docs/specs/active/subspecs/development-agent-pipeline.md"
        path.parent.mkdir(parents=True)
        path.write_text("## A6 — planned\n## A7 — planned\n", encoding="utf-8")
        self.state = self.repo / "docs/specs/STATE.json"
        self.payload = {
            "schemaVersion": 1, "mode": "SINGLE", "selectedWorkstreamId": "pipeline",
            "workstreams": [{"id": "pipeline", "specPath": "docs/specs/active/subspecs/development-agent-pipeline.md",
                            "stageId": "A6", "phase": "DESIGN", "runId": None,
                            "developerDecision": "UNRECORDED"}],
        }

    def validate(self, payload):
        self.state.write_text(json.dumps(payload), encoding="utf-8")
        return module.validate_state(self.state, self.repo)

    def test_valid_single_workstream(self):
        self.assertEqual("SINGLE", self.validate(self.payload)["mode"])

    def test_rejects_unknown_and_duplicate_fields(self):
        value = copy.deepcopy(self.payload)
        value["unexpected"] = "bypass"
        with self.assertRaisesRegex(ValueError, "fields"):
            self.validate(value)
        self.state.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate key"):
            module.validate_state(self.state, self.repo)

    def test_rejects_foreign_or_missing_spec_and_stage(self):
        for path in ("../outside.md", "docs/specs/archive/subspecs/foo.md",
                     "docs/specs/active/subspecs/missing.md"):
            value = copy.deepcopy(self.payload)
            value["workstreams"][0]["specPath"] = path
            with self.assertRaises(ValueError):
                self.validate(value)
        value = copy.deepcopy(self.payload)
        value["workstreams"][0]["stageId"] = "A5"
        with self.assertRaisesRegex(ValueError, "not found"):
            self.validate(value)

    def test_rejects_unapproved_concurrency(self):
        value = copy.deepcopy(self.payload)
        other = self.repo / "docs/specs/active/subspecs/other.md"
        other.write_text("## A7 — planned\n", encoding="utf-8")
        value["workstreams"].append({**value["workstreams"][0], "id": "another", "stageId": "A7", "specPath": "docs/specs/active/subspecs/other.md"})
        with self.assertRaisesRegex(ValueError, "SINGLE mode"):
            self.validate(value)
        value["workstreams"][1]["phase"] = "PAUSED"
        self.validate(value)
        value["mode"] = "MULTI"
        with self.assertRaisesRegex(ValueError, "Only SINGLE"):
            self.validate(value)

    def test_repository_schema_matches_code_enums_and_required_fields(self):
        schema = json.loads((LOCATION.parents[2] / "docs/specs/state.schema.json").read_text())
        props = schema["properties"]
        self.assertEqual(set(props), set(schema["required"]))
        item = props["workstreams"]["items"]
        self.assertEqual(set(item["properties"]), set(item["required"]))
        self.assertEqual(set(item["properties"]["phase"]["enum"]), module.PHASES)
        self.assertEqual(["SINGLE"], [props["mode"]["const"]])

    def test_rejects_fabricated_acceptance_or_unbound_run(self):
        value = copy.deepcopy(self.payload)
        value["workstreams"][0]["phase"] = "ACCEPTED"
        with self.assertRaisesRegex(ValueError, "must agree"):
            self.validate(value)
        value = copy.deepcopy(self.payload)
        value["workstreams"][0]["developerDecision"] = "ACCEPTED"
        with self.assertRaisesRegex(ValueError, "must agree"):
            self.validate(value)
        value = copy.deepcopy(self.payload)
        value["workstreams"][0]["phase"] = "RUNNING"
        with self.assertRaisesRegex(ValueError, "runId"):
            self.validate(value)


if __name__ == "__main__":
    unittest.main()
