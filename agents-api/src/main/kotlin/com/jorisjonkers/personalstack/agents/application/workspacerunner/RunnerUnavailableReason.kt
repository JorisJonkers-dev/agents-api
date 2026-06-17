package com.jorisjonkers.personalstack.agents.application.workspacerunner

enum class RunnerUnavailableReason(
    val label: String,
) {
    BOOT_LEASE_HELD("boot_lease_held"),
    SETUP_OPERATION_IN_PROGRESS("setup_operation_in_progress"),
    NOT_READY_AFTER_PROVISION("not_ready_after_provision"),
    PROVISION_FAILED("provision_failed"),
    WORKSPACE_NOT_FOUND("workspace_not_found"),
}
