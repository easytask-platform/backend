package com.easytask.backend.role;

/**
 * How far a role can see (decision D11). Permissions gate actions; the scope
 * gates list/detail visibility — the tiers formerly implied by the role enum.
 */
public enum DataScope {
    /** Everything in the organization (legacy ORGANIZATION_ADMIN tier). */
    ORGANIZATION,
    /** Managed teams/projects plus own assignments (legacy MANAGER tier). */
    MANAGED,
    /** Own assignments and member projects only (legacy EMPLOYEE tier). */
    ASSIGNED
}
