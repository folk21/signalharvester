#!/usr/bin/env python3
"""Independent contract checks for the A4.2a read-only single-run evidence report."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def put(path: Path, obj: object) -> None:
    path.write_text(json.dumps(obj, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class EvidenceOracle(unittest.TestCase):
    controller = None

    def setUp(self) -> None:
        td = tempfile.TemporaryDirectory()
        self.addCleanup(td.cleanup)
        self.run = Path(td.name)
        self.manifest = {
            'schemaVersion': 1, 'runId': 'run-EA-01', 'stage': 'A4',
            'specPath': 'docs/specs/active/subspecs/development-agent-pipeline.md',
            'taskSha256': '1'*64, 'policy': {'sha256': '2'*64},
            'repository': {'baseCommit': '3'*40},
            'requiredChecks': ['gitleaks','canonical'],
        }
        self.worker = {
            'schemaVersion': 1, 'attempt': 1, 'status': 'PASS', 'failureReason': None,
            'taskSha256': '1'*64, 'model': 'worker', 'changeSha256': '4'*64,
            'changedFiles': ['tools/agent-pipeline/agent_pipeline.py'],
            'logSha256': '5'*64, 'command': ['raw_command_secret'],
            'log': 'raw_log_secret', 'preflight': {'rawOutput': 'raw_transcript_secret'},
        }
        self.checks = [
            {'name': 'gitleaks', 'attempt': 1, 'status': 'PASS', 'required': True, 'command': ['raw_command_secret']},
            {'name': 'canonical', 'attempt': 1, 'status': 'FAIL', 'required': True, 'log': 'raw_log_secret'},
        ]
        self.status = {
            'schemaVersion': 1, 'runId': 'run-EA-01', 'stage': 'A4', 'state': 'BLOCKED',
            'checks': {
                'gitleaks': {'attempt': 1, 'status': 'PASS', 'required': True},
                'canonical': {'attempt': 1, 'status': 'FAIL', 'required': True},
            },
            'worker': {'present': True, 'required': True, 'status': 'PASS'},
            'review': {'present': False, 'verdict': None, 'openFindingCount': 0},
            'mainReviewRequiredBeforeAcceptance': True,
        }
        self.persist()

    def persist(self) -> None:
        put(self.run/'manifest.json', self.manifest)
        put(self.run/'worker.json', self.worker)
        put(self.run/'checks.json', {'schemaVersion': 1, 'checks': self.checks})
        put(self.run/'status.json', self.status)

    def report(self) -> dict:
        return self.controller.summarize_run_evidence(self.run)

    def reject(self) -> None:
        with self.assertRaises((ValueError, TypeError, KeyError, json.JSONDecodeError)):
            self.report()

    def ready(self) -> None:
        self.checks[1]['status'] = 'PASS'
        self.status['checks']['canonical']['status'] = 'PASS'
        self.status['state'] = 'READY_FOR_MAIN_REVIEW'
        self.status['review'] = {'present': True, 'verdict': 'PASS', 'openFindingCount': 0}
        self.persist()
        put(self.run/'review.json', {'schemaVersion': 1, 'reviewer': 'reviewer', 'verdict': 'PASS', 'findings': []})

    def test_ea01_blocked_source_identity(self) -> None:
        report = self.report()
        self.assertEqual(('run-EA-01', 'A4', 'BLOCKED'), (report['runId'], report['stage'], report['state']))
        self.assertEqual('3'*40, report['baseCommit'])
        self.assertEqual('1'*64, report['taskSha256'])
        self.assertEqual('2'*64, report['policySha256'])
        self.assertTrue(report['requiresExternalAcceptance'])
        self.assertNotIn('accepted', report)

    def test_ea02_ready_requires_explicit_pass_but_not_accepted(self) -> None:
        self.ready()
        result = self.report()
        self.assertEqual('READY_FOR_MAIN_REVIEW', result['state'])
        self.assertTrue(result['review']['present'])
        self.assertEqual('PASS', result['review']['verdict'])
        self.assertEqual({'PASS'}, {item['status'] for item in result['checks']})
        self.assertTrue(result['requiresExternalAcceptance'])
        self.assertNotIn('accepted', result)

    def test_ea03_no_review_never_becomes_pass(self) -> None:
        result = self.report()
        self.assertFalse(result['review']['present'])
        self.assertIsNone(result['review']['verdict'])
        self.assertNotIn('review.json', result['evidenceDigests'])

    def test_ea04_ordered_snapshot_digests(self) -> None:
        for n in (1,2,3):
            put(self.run/f'worker-attempt-{n}.json', dict(self.worker, attempt=n))
        self.worker['attempt'] = 3
        put(self.run/'worker.json', self.worker)
        result = self.report()
        self.assertEqual([1,2,3], [x['attempt'] for x in result['workerAttempts']])
        for x in result['workerAttempts']:
            self.assertEqual(sha(self.run/x['evidenceFile']), x['evidenceSha256'])

    def test_ea05_missing_worker_is_not_invented(self) -> None:
        (self.run/'worker.json').unlink()
        self.status['worker'] = {'present': False, 'required': True, 'status': None}
        put(self.run/'status.json', self.status)
        result = self.report()
        self.assertEqual([], result['workerAttempts'])
        self.assertNotIn('worker.json', result['evidenceDigests'])

    def test_ea06_malformed_or_array_manifest_fails_closed(self) -> None:
        for payload in ('[]\n','{broken'):
            with self.subTest(payload=payload):
                (self.run/'manifest.json').write_text(payload, encoding='utf-8')
                self.reject()

    def test_ea07_latest_snapshot_mismatch(self) -> None:
        put(self.run/'worker-attempt-1.json', self.worker)
        self.worker['model'] = 'changed'
        put(self.run/'worker.json', self.worker)
        self.reject()

    def test_ea08_task_sha_mismatch_latest_or_snapshot(self) -> None:
        self.worker['taskSha256'] = '9'*64
        put(self.run/'worker.json', self.worker)
        self.reject()
        self.worker['taskSha256'] = '1'*64
        self.persist()
        put(self.run/'worker-attempt-1.json', dict(self.worker, taskSha256='9'*64))
        self.reject()

    def test_ea09_status_check_disagreement(self) -> None:
        self.status['checks']['canonical']['status'] = 'PASS'
        put(self.run/'status.json', self.status)
        self.reject()

    def test_ea10_unsupported_ready_without_check_or_review(self) -> None:
        self.status['state'] = 'READY_FOR_MAIN_REVIEW'
        put(self.run/'status.json', self.status)
        self.reject()
        self.checks[1]['status'] = 'PASS'
        self.status['checks']['canonical']['status'] = 'PASS'
        self.persist()
        self.reject()

    def test_ea11_no_raw_fields(self) -> None:
        result = json.dumps(self.report())
        for needle in ('raw_command_secret','raw_log_secret','raw_transcript_secret'):
            self.assertNotIn(needle, result)

    def test_ea12_file_digest_current_bytes(self) -> None:
        result = self.report()
        self.assertEqual(sha(self.run/'checks.json'), result['evidenceDigests']['checks.json'])
        with (self.run/'checks.json').open('ab') as out:
            out.write(b'\n')
        self.assertNotEqual(sha(self.run/'checks.json'), result['evidenceDigests']['checks.json'])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--evidence-dir', type=Path)
    parser.add_argument('--aggregate-output', type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('a42_candidate', args.candidate.resolve())
    if spec is None or spec.loader is None:
        raise ValueError('Unable to import candidate module')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    EvidenceOracle.controller = module
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(EvidenceOracle))
    summary = {
        'schemaVersion': 1, 'oracle': 'a4.2a-run-evidence',
        'status': 'PASS' if result.wasSuccessful() else 'FAIL',
        'testsRun': result.testsRun, 'failures': len(result.failures),
        'errors': len(result.errors), 'skipped': len(result.skipped),
        'failingTests': [str(test) for test, _ in result.failures + result.errors],
    }
    if args.evidence_dir is not None or args.aggregate_output is not None:
        if args.evidence_dir is None or args.aggregate_output is None:
            parser.error('--evidence-dir and --aggregate-output must be provided together')
        if result.wasSuccessful():
            try:
                aggregate = module.summarize_run_evidence(args.evidence_dir)
                args.aggregate_output.parent.mkdir(parents=True, exist_ok=True)
                put(args.aggregate_output, aggregate)
            except Exception as exc:
                summary['status'] = 'FAIL'
                summary['aggregateErrorType'] = type(exc).__name__
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        put(args.report, summary)
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
