#!/usr/bin/env python3
"""Compatibility CLI for the modular development-agent pipeline.

The implementation lives in agent_pipeline_lib; preserve historical imports and CLI.
"""
from __future__ import annotations

import socket
import subprocess
import sys
from pathlib import Path

_TOOLS_DIR = str(Path(__file__).resolve().parent)
if _TOOLS_DIR not in sys.path:
    sys.path.insert(0, _TOOLS_DIR)

_ORIGINAL_BYTECODE_SETTING = sys.dont_write_bytecode
sys.dont_write_bytecode = True
try:
    from agent_pipeline_lib import core as core_module
    from agent_pipeline_lib import policy as policy_module
    from agent_pipeline_lib import reviewer_policy as reviewer_policy_module
    from agent_pipeline_lib import git_ops as git_ops_module
    from agent_pipeline_lib import aider_runtime as aider_runtime_module
    from agent_pipeline_lib import stage_scope as stage_scope_module
    from agent_pipeline_lib import runs as runs_module
    from agent_pipeline_lib import checks as checks_module
    from agent_pipeline_lib import reviewer_contract as reviewer_contract_module
    from agent_pipeline_lib import reviewer_transport as reviewer_transport_module
    from agent_pipeline_lib import worker_attempts as worker_attempts_module
    from agent_pipeline_lib import deviations as deviations_module
    from agent_pipeline_lib import run_evidence as run_evidence_module
    from agent_pipeline_lib import subspec_evidence as subspec_evidence_module
    from agent_pipeline_lib import subspec_export as subspec_export_module
    from agent_pipeline_lib import promotion as promotion_module
    from agent_pipeline_lib import stage_init as stage_init_module
    from agent_pipeline_lib import stage_sequence as stage_sequence_module
    from agent_pipeline_lib import handoff as handoff_module
    from agent_pipeline_lib import workspaces as workspaces_module
    from agent_pipeline_lib import worker_exec as worker_exec_module
    from agent_pipeline_lib import repair as repair_module
    from agent_pipeline_lib import cycle as cycle_module
    from agent_pipeline_lib import status as status_module
    from agent_pipeline_lib import cli_parser as cli_parser_module
    from agent_pipeline_lib import cli_main as cli_main_module
finally:
    sys.dont_write_bytecode = _ORIGINAL_BYTECODE_SETTING

SCHEMA_VERSION = core_module.SCHEMA_VERSION
RUN_ID_GRAMMAR = core_module.RUN_ID_GRAMMAR
STATE_PREPARED = core_module.STATE_PREPARED
STATE_WORKER_REQUIRED = core_module.STATE_WORKER_REQUIRED
STATE_VERIFYING = core_module.STATE_VERIFYING
STATE_REVIEW_REQUIRED = core_module.STATE_REVIEW_REQUIRED
STATE_BLOCKED = core_module.STATE_BLOCKED
STATE_NEEDS_MAIN_REVIEW = core_module.STATE_NEEDS_MAIN_REVIEW
STATE_READY_FOR_MAIN_REVIEW = core_module.STATE_READY_FOR_MAIN_REVIEW
VALID_REVIEW_VERDICTS = core_module.VALID_REVIEW_VERDICTS
VALID_FINDING_SEVERITIES = core_module.VALID_FINDING_SEVERITIES
VALID_FINDING_STATUSES = core_module.VALID_FINDING_STATUSES
RISK_ORDER = core_module.RISK_ORDER
PROTECTED_WORKER_CONTROL_PATHS = core_module.PROTECTED_WORKER_CONTROL_PATHS
utc_now = core_module.utc_now
read_json = core_module.read_json
write_json = core_module.write_json
sha256_file = core_module.sha256_file
run_git = git_ops_module.run_git
discover_repository_state = git_ops_module.discover_repository_state
validate_policy = policy_module.validate_policy
_safe_reviewer_endpoint = reviewer_policy_module._safe_reviewer_endpoint
_reviewer_endpoint_registry = reviewer_policy_module._reviewer_endpoint_registry
_validated_openrouter_routing = reviewer_policy_module._validated_openrouter_routing
authorized_worker_model = policy_module.authorized_worker_model
parse_version_tuple = aider_runtime_module.parse_version_tuple
prepare_aider_runtime_config = aider_runtime_module.prepare_aider_runtime_config
build_aider_environment = aider_runtime_module.build_aider_environment
_aider_context_functions = aider_runtime_module._aider_context_functions
configured_aider_flags = aider_runtime_module.configured_aider_flags
preflight_aider = aider_runtime_module.preflight_aider
validate_repository = policy_module.validate_repository
validate_active_spec = policy_module.validate_active_spec
required_check_map = policy_module.required_check_map
build_worker_task = runs_module.build_worker_task
safe_stage_path = stage_scope_module.safe_stage_path
stage_scope_contains = stage_scope_module.stage_scope_contains
stage_context = stage_scope_module.stage_context
validate_stage_packet = stage_scope_module.validate_stage_packet
stage_source_evidence = stage_scope_module.stage_source_evidence
ensure_stage_contract = stage_scope_module.ensure_stage_contract
enforce_stage_scope = stage_scope_module.enforce_stage_scope
create_run = runs_module.create_run
load_run = runs_module.load_run
append_history = runs_module.append_history
execute_check = checks_module.execute_check
execute_gitleaks_check = checks_module.execute_gitleaks_check
run_required_check = checks_module.run_required_check
validate_security_check_evidence = checks_module.validate_security_check_evidence
validate_review = reviewer_contract_module.validate_review
_review_failure_reason_code = reviewer_contract_module._review_failure_reason_code
_review_schema_failure_code = reviewer_contract_module._review_schema_failure_code
_review_completion_code = reviewer_contract_module._review_completion_code
ReviewerProviderFailure = reviewer_contract_module.ReviewerProviderFailure
_review_request_body = reviewer_contract_module._review_request_body
_RejectReviewerRedirect = reviewer_transport_module._RejectReviewerRedirect
_assert_public_reviewer_dns = reviewer_transport_module._assert_public_reviewer_dns
_open_reviewer_request = reviewer_transport_module._open_reviewer_request
_invoke_reviewer_provider = reviewer_transport_module._invoke_reviewer_provider
probe_independent_reviewer = reviewer_transport_module.probe_independent_reviewer
run_independent_review = reviewer_transport_module.run_independent_review
WORKER_ATTEMPT_PATTERN = worker_attempts_module.WORKER_ATTEMPT_PATTERN
collect_worker_attempts = worker_attempts_module.collect_worker_attempts
_worker_attempt_summary = worker_attempts_module._worker_attempt_summary
EVIDENCE_DIGEST_FILES = run_evidence_module.EVIDENCE_DIGEST_FILES
DEVIATION_FILENAME_PATTERN = deviations_module.DEVIATION_FILENAME_PATTERN
DEVIATION_SCHEMA_VERSION = deviations_module.DEVIATION_SCHEMA_VERSION
DEVIATION_REPORTED_BY = deviations_module.DEVIATION_REPORTED_BY
DEVIATION_STATE_UNREPORTED = deviations_module.DEVIATION_STATE_UNREPORTED
DEVIATION_STATE_REPORTED_NONE = deviations_module.DEVIATION_STATE_REPORTED_NONE
DEVIATION_STATE_REPORTED = deviations_module.DEVIATION_STATE_REPORTED
DEVIATION_KINDS = deviations_module.DEVIATION_KINDS
DEVIATION_RISK_IMPACTS = deviations_module.DEVIATION_RISK_IMPACTS
DEVIATION_MAX_RECORDS = deviations_module.DEVIATION_MAX_RECORDS
DEVIATION_MAX_TEXT = deviations_module.DEVIATION_MAX_TEXT
DEVIATION_ID_PATTERN = deviations_module.DEVIATION_ID_PATTERN
DEVIATION_SHA256_PATTERN = deviations_module.DEVIATION_SHA256_PATTERN
DEVIATION_TOP_LEVEL_KEYS = deviations_module.DEVIATION_TOP_LEVEL_KEYS
DEVIATION_ENTRY_KEYS = deviations_module.DEVIATION_ENTRY_KEYS
_require_bounded_text = deviations_module._require_bounded_text
_require_sha256 = deviations_module._require_sha256
_normalize_deviation_path = deviations_module._normalize_deviation_path
_validate_deviation_payload = deviations_module._validate_deviation_payload
_compact_deviation_entry = deviations_module._compact_deviation_entry
collect_deviation_summaries = deviations_module.collect_deviation_summaries
_require_json_object = run_evidence_module._require_json_object
_require_positive_int = run_evidence_module._require_positive_int
_validate_worker_attempt_task_digest = run_evidence_module._validate_worker_attempt_task_digest
_bounded_check_summary = run_evidence_module._bounded_check_summary
summarize_run_evidence = run_evidence_module.summarize_run_evidence
SUBSPEC_MAX_STAGES = subspec_evidence_module.SUBSPEC_MAX_STAGES
SUBSPEC_MAX_RUNS = subspec_evidence_module.SUBSPEC_MAX_RUNS
summarize_subspec_evidence = subspec_evidence_module.summarize_subspec_evidence
SUBSPEC_EXPORT_MAX_FILES = subspec_export_module.SUBSPEC_EXPORT_MAX_FILES
SUBSPEC_EXPORT_MAX_FILE_BYTES = subspec_export_module.SUBSPEC_EXPORT_MAX_FILE_BYTES
SUBSPEC_EXPORT_MAX_TOTAL_BYTES = subspec_export_module.SUBSPEC_EXPORT_MAX_TOTAL_BYTES
SUBSPEC_EXPORT_FILENAME = subspec_export_module.SUBSPEC_EXPORT_FILENAME
_subspec_run_entries = subspec_export_module._subspec_run_entries
_subspec_export_files = subspec_export_module._subspec_export_files
_verified_subspec_export = subspec_export_module._verified_subspec_export
_compact_subspec_review_details = subspec_export_module._compact_subspec_review_details
export_subspec_handoff = subspec_export_module.export_subspec_handoff
PROMOTION_BRANCH_PATTERN = promotion_module.PROMOTION_BRANCH_PATTERN
_promotion_git = promotion_module._promotion_git
_promotion_safe_check_logs = promotion_module._promotion_safe_check_logs
_promotion_branch_is_checked_out = promotion_module._promotion_branch_is_checked_out
promote_verified_stage = promotion_module.promote_verified_stage
initialize_stage_run = stage_init_module.initialize_stage_run
prepare_next_stage = stage_init_module.prepare_next_stage
SEQUENCE_MAX_STAGES = stage_sequence_module.SEQUENCE_MAX_STAGES
inspect_stage_sequence = stage_sequence_module.inspect_stage_sequence
plan_stage_batch = stage_sequence_module.plan_stage_batch
drive_stage_sequence = stage_sequence_module.drive_stage_sequence
write_handoff = handoff_module.write_handoff
discover_changed_files = git_ops_module.discover_changed_files
classify_risk = status_module.classify_risk
run_git_mutation = git_ops_module.run_git_mutation
relative_or_absolute = git_ops_module.relative_or_absolute
resolve_manifest_path = git_ops_module.resolve_manifest_path
safe_branch_component = git_ops_module.safe_branch_component
prepare_worker_workspace = workspaces_module.prepare_worker_workspace
execution_root = workspaces_module.execution_root
build_aider_command = worker_exec_module.build_aider_command
workspace_change_digest = workspaces_module.workspace_change_digest
ensure_worker_workspace_matches_result = workspaces_module.ensure_worker_workspace_matches_result
ensure_source_matches_worker_result = workspaces_module.ensure_source_matches_worker_result
execute_worker = worker_exec_module.execute_worker
_latest_checks = repair_module._latest_checks
prepare_repair = repair_module.prepare_repair
python_unittest_diagnostic = repair_module.python_unittest_diagnostic
classify_automatic_check_failure = repair_module.classify_automatic_check_failure
run_automated_cycle = cycle_module.run_automated_cycle
derive_state = status_module.derive_state
update_status = status_module.update_status
ensure_run_contract_matches = runs_module.ensure_run_contract_matches
parse_command_tail = cli_parser_module.parse_command_tail
build_parser = cli_parser_module.build_parser
resolve_under_repo = cli_main_module.resolve_under_repo
main = cli_main_module.main


# Preserve the legacy test/extension seam without coupling subsystem imports to the facade.
_ORIGINAL_UPDATE_STATUS = update_status
_ORIGINAL_PREPARE_WORKER_WORKSPACE = prepare_worker_workspace
_ORIGINAL_RUN_AUTOMATED_CYCLE = run_automated_cycle


def write_handoff(repo_root, run_dir, manifest, policy):
    """Expose the original facade patch point for standalone oracle integrations."""
    callback = (update_status if update_status is not _ORIGINAL_UPDATE_STATUS
                else status_module.update_status)
    return handoff_module.write_handoff(
        repo_root, run_dir, manifest, policy, _update_status=callback
    )


def drive_stage_sequence(repo_root, worktrees_root, run_dir, manifest, policy_path,
                         policy, *, expected_stages, feature_branch,
                         previous_run_dir=None, execute=False, developer_approved=False):
    """Forward legacy patched boundaries to the modular stage driver."""
    workspace = (prepare_worker_workspace
                 if prepare_worker_workspace is not _ORIGINAL_PREPARE_WORKER_WORKSPACE
                 else workspaces_module.prepare_worker_workspace)
    cycle = (run_automated_cycle if run_automated_cycle is not _ORIGINAL_RUN_AUTOMATED_CYCLE
             else cycle_module.run_automated_cycle)
    status = (update_status if update_status is not _ORIGINAL_UPDATE_STATUS
              else status_module.update_status)
    return stage_sequence_module.drive_stage_sequence(
        repo_root, worktrees_root, run_dir, manifest, policy_path, policy,
        expected_stages=expected_stages, feature_branch=feature_branch,
        previous_run_dir=previous_run_dir, execute=execute,
        developer_approved=developer_approved, _prepare_workspace=workspace,
        _run_cycle=cycle, _update_status=status,
    )

if __name__ == "__main__":
    raise SystemExit(main())
