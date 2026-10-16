#!/usr/bin/env python3
"""Independent SE acceptance cases for read-only multi-run sub-spec evidence."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC_PATH = 'docs/specs/active/subspecs/development-agent-pipeline.md'
PLAN_PATH = 'docs/specs/active/subspecs/development-agent-pipeline-implementation-plan.md'


def write(path: Path, record: object) -> None:
    path.write_text(json.dumps(record, sort_keys=True) + '\n', encoding='utf-8')


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SubspecOracle(unittest.TestCase):
    controller = None

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)

    def stage(self, stage: str, run_id: str, ready: bool = False) -> Path:
        directory = self.base / run_id
        directory.mkdir()
        packet = {'schemaVersion': 1, 'stageId': stage, 'specPath': SPEC_PATH,
                  'implementationPlanPath': PLAN_PATH, 'requirementIds': ['AP13'], 'featureIds': []}
        write(directory / 'stage-packet.json', packet)
        manifest = {
            'schemaVersion': 1, 'runId': run_id, 'stage': stage,
            'specPath': SPEC_PATH, 'taskSha256': '1' * 64,
            'policy': {'sha256': '2' * 64}, 'repository': {'baseCommit': '3' * 40},
            'requiredChecks': ['canonical'],
            'stagePacket': {
                'sha256': sha(directory / 'stage-packet.json'), 'specPath': SPEC_PATH,
                'planPath': PLAN_PATH, 'contextSha256': {SPEC_PATH: 'a' * 64, PLAN_PATH: 'b' * 64},
                'requirementIds': ['AP13'], 'featureIds': [],
            },
        }
        worker = {'schemaVersion': 1, 'attempt': 1, 'status': 'PASS',
                  'taskSha256': '1' * 64, 'changeSha256': '4' * 64,
                  'changedFiles': ['tools/agent-pipeline/agent_pipeline.py'],
                  'preflight': {'raw': 'raw_transcript_do_not_show'}}
        check = {'name': 'canonical', 'attempt': 1,
                 'status': 'PASS' if ready else 'FAIL', 'required': True,
                 'command': ['raw_command_do_not_show']}
        status = {'schemaVersion': 1, 'runId': run_id, 'stage': stage,
                  'state': 'READY_FOR_MAIN_REVIEW' if ready else 'BLOCKED',
                  'checks': {'canonical': {key: check[key] for key in ('status', 'attempt', 'required')}},
                  'review': {'present': ready, 'verdict': 'PASS' if ready else None}}
        for name, obj in [('manifest', manifest), ('worker', worker),
                          ('checks', {'checks': [check]}), ('status', status)]:
            write(directory / f'{name}.json', obj)
        if ready:
            write(directory / 'review.json', {'verdict': 'PASS', 'findings': []})
        return directory

    def report(self, runs: list[Path], stages: list[str]) -> dict:
        return self.controller.summarize_subspec_evidence(runs, spec_path=SPEC_PATH, expected_stages=stages)

    def test_se01_ready_does_not_claim_acceptance(self) -> None:
        result = self.report([self.stage('A4.3', 'run-a', True)], ['A4.3'])
        self.assertTrue(result['requiresExternalAcceptance'])
        self.assertNotIn('accepted', json.dumps(result).lower())

    def test_se02_missing_stage_remains_missing(self) -> None:
        result = self.report([self.stage('A4.3', 'run-a', True)], ['A4.3', 'A4.4'])
        self.assertEqual(['A4.4'], result['missingStages'])

    def test_se03_blocked_run_not_lost_by_ready_run(self) -> None:
        old = self.stage('A4.3', 'run-old')
        new = self.stage('A4.3', 'run-new', True)
        runs = self.report([old, new], ['A4.3'])['stages'][0]['runs']
        self.assertEqual(['BLOCKED', 'READY_FOR_MAIN_REVIEW'], [x['state'] for x in runs])

    def test_se04_packet_digest_drift_rejected(self) -> None:
        path = self.stage('A4.3', 'run-a')
        (path / 'stage-packet.json').write_text('{}', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.report([path], ['A4.3'])

    def test_se05_foreign_spec_and_plan_rejected(self) -> None:
        a = self.stage('A4.3', 'run-a')
        b = self.stage('A4.4', 'run-b')
        record = json.loads((b / 'manifest.json').read_text())
        record['stagePacket']['planPath'] = None
        write(b / 'manifest.json', record)
        with self.assertRaises(ValueError):
            self.report([a, b], ['A4.3', 'A4.4'])
        record['stagePacket']['planPath'] = PLAN_PATH
        record['specPath'] = 'other.md'
        write(b / 'manifest.json', record)
        with self.assertRaises(ValueError):
            self.report([a, b], ['A4.3', 'A4.4'])

    def test_se06_no_raw_transcript_or_check_command(self) -> None:
        result = json.dumps(self.report([self.stage('A4.3', 'run-a')], ['A4.3']))
        self.assertNotIn('raw_transcript_do_not_show', result)
        self.assertNotIn('raw_command_do_not_show', result)

    def test_se07_read_only_and_symlink_rejected(self) -> None:
        path = self.stage('A4.3', 'run-a')
        before = {x.name: x.read_bytes() for x in path.iterdir()}
        self.report([path], ['A4.3'])
        self.assertEqual(before, {x.name: x.read_bytes() for x in path.iterdir()})
        (path / 'stage-packet.json').rename(path / 'real-packet.json')
        (path / 'stage-packet.json').symlink_to('real-packet.json')
        with self.assertRaises(ValueError):
            self.report([path], ['A4.3'])

    def test_se08_reviewer_attempt_must_bind_worker_source(self) -> None:
        path = self.stage('A4.3', 'run-a', True)
        write(path / 'review-attempt.json', {'status': 'PASS', 'workerSha256': 'f' * 64})
        with self.assertRaises(ValueError):
            self.report([path], ['A4.3'])

    def test_se09_source_version_drift_is_visible(self) -> None:
        a = self.stage('A4.3', 'run-a')
        b = self.stage('A4.4', 'run-b')
        record = json.loads((b / 'manifest.json').read_text())
        record['stagePacket']['contextSha256'][SPEC_PATH] = 'c' * 64
        write(b / 'manifest.json', record)
        self.assertEqual(['a' * 64, 'c' * 64],
                         self.report([a, b], ['A4.3', 'A4.4'])['specSourceDigests'])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('subspec_oracle_candidate', args.candidate)
    if spec is None or spec.loader is None:
        raise ValueError('Missing candidate module')
    controller = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(controller)
    SubspecOracle.controller = controller
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(SubspecOracle)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({
        'oracle': 'a4.3-subspec-evidence', 'schemaVersion': 1,
        'testsRun': result.testsRun, 'failures': len(result.failures),
        'errors': len(result.errors), 'status': 'PASS' if result.wasSuccessful() else 'FAIL',
    }, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    raise SystemExit(main())
