#!/usr/bin/env python3
"""Independent A4.4 custody and safe handoff contract checks."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from subspec_evidence_oracle import SPEC_PATH, SubspecOracle


class PrivateHandoffOracle(unittest.TestCase):
    controller = None

    def setUp(self) -> None:
        self.fixture = SubspecOracle('test_se01_ready_does_not_claim_acceptance')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.out = self.root / 'private-evidence'

    def export(self, runs: list[Path], stages: list[str]) -> dict:
        return self.controller.export_subspec_handoff(
            runs, spec_path=SPEC_PATH, expected_stages=stages,
            destination=self.out, repo_root=self.repo,
        )

    def test_hc01_survives_clean_like_source_deletion(self) -> None:
        original = self.fixture.stage('A4.3', 'run-a')
        self.export([original], ['A4.3'])
        shutil.rmtree(original)
        self.assertEqual('PASS', self.controller._verified_subspec_export(self.out)['status'])

    def test_hc02_sensitive_raw_fields_only_private(self) -> None:
        original = self.fixture.stage('A4.3', 'run-a')
        self.export([original], ['A4.3'])
        compact = (self.out / 'review-handoff.json').read_text()
        self.assertNotIn('raw_transcript_do_not_show', compact)
        self.assertNotIn('raw_command_do_not_show', compact)
        self.assertIn('raw_transcript_do_not_show',
                      (self.out / 'runs/run-a/worker.json').read_text())

    def test_hc03_fail_closed_on_packet_or_inventory_drift(self) -> None:
        original = self.fixture.stage('A4.3', 'run-a')
        self.export([original], ['A4.3'])
        (self.out / 'runs/run-a/stage-packet.json').write_text('{}\n')
        with self.assertRaises(ValueError):
            self.controller._verified_subspec_export(self.out)

    def test_hc04_blocked_history_and_missing_stage_retained(self) -> None:
        old = self.fixture.stage('A4.3', 'run-old')
        new = self.fixture.stage('A4.3', 'run-new', True)
        self.export([old, new], ['A4.3', 'A4.4'])
        report = json.loads((self.out / 'review-handoff.json').read_text())
        self.assertEqual(['A4.4'], report['summary']['missingStages'])
        states = [r['state'] for r in report['summary']['stages'][0]['runs']]
        self.assertEqual(['BLOCKED', 'READY_FOR_MAIN_REVIEW'], states)
        self.assertTrue(report['requiresExternalAcceptance'])

    def test_hc05_no_clobber_or_repository_destination(self) -> None:
        original = self.fixture.stage('A4.3', 'run-a')
        self.export([original], ['A4.3'])
        with self.assertRaises(ValueError):
            self.export([original], ['A4.3'])
        with self.assertRaises(ValueError):
            self.controller.export_subspec_handoff(
                [original], spec_path=SPEC_PATH, expected_stages=['A4.3'],
                destination=self.repo / 'unsafe', repo_root=self.repo)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('handoff_oracle_candidate', args.candidate)
    if spec is None or spec.loader is None:
        raise ValueError('Missing candidate module')
    controller = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(controller)
    PrivateHandoffOracle.controller = controller
    tests = unittest.defaultTestLoader.loadTestsFromTestCase(PrivateHandoffOracle)
    result = unittest.TextTestRunner(verbosity=2).run(tests)
    print(json.dumps({'oracle': 'a4.4-private-subspec-handoff', 'schemaVersion': 1,
                      'testsRun': result.testsRun, 'failures': len(result.failures),
                      'errors': len(result.errors),
                      'status': 'PASS' if result.wasSuccessful() else 'FAIL'}, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    raise SystemExit(main())
