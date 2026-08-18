package com.easytask.backend.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface SecurityAuditEventRepository
        extends JpaRepository<SecurityAuditEvent, UUID>, JpaSpecificationExecutor<SecurityAuditEvent> {
}
