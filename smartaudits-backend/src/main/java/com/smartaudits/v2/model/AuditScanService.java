package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Internal V8 operations on Audits and Scans (ADR-0001). No controller or endpoint calls them.
 *
 * <p>Transaction boundary: every public method is one complete READ_COMMITTED transaction run by
 * a TransactionTemplate over the application's JPA transaction manager, with a finite timeout
 * that Spring also applies to every query. Calling it inside an existing transaction is rejected
 * before anything is read or written, so it can never join a caller's transaction silently.
 *
 * <p>Open EntityManager in View is enabled by default in this application: during a web request
 * a thread-bound EntityManager exists without an active transaction. That is accepted, because
 * JpaTransactionManager then begins this operation's transaction on that same EntityManager. The
 * operation refuses to start when that context holds unflushed changes (it would otherwise commit
 * them), reads every decisive value with scalar queries or a refresh taken after the Audit lock
 * (never from possibly stale cached entities), and returns only immutable results. On rollback
 * Spring clears a pre-bound EntityManager, so a failed context is never reused.
 *
 * <p>Invocation policy for a future endpoint or worker: call these methods directly, outside any
 * transaction; pass a trusted actor id; map EntityNotFoundException to 404 and
 * IllegalStateException to a rejected request; never retry a creation automatically when the
 * commit outcome is uncertain (unique violation, optimistic conflict, deadlock, lock timeout or
 * any persistence error ends the operation). Authorization is checked on every call and again
 * after waiting for a lock; an operation already in progress is not revoked retroactively.
 */
@Service
public class AuditScanService {
    static final int TIMEOUT_SECONDS = 10;

    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public AuditScanService(EntityManagerFactory entityManagerFactory, PlatformTransactionManager transactionManager) {
        this(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), transactionManager, Clock.systemUTC());
    }

    /** Tests and probes inject the UTC clock; production uses the system UTC clock. */
    AuditScanService(EntityManager entityManager, PlatformTransactionManager transactionManager, Clock clock) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        template.setName(AuditScanService.class.getName());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TIMEOUT_SECONDS);
        template.setReadOnly(false);
        this.transactions = template;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Committed Audit together with its first Scan. */
    public record AuditCreation(long auditId, long scanId, int attemptNumber, ScanStatus status) { }

    /** Committed state of one Scan after a retry or a cancellation. */
    public record ScanOutcome(long auditId, long scanId, int attemptNumber, ScanStatus status) { }

    /** Creates a new Audit and its PENDING attempt 1 in one transaction; never one without the other. */
    public AuditCreation createAudit(long organizationId, long siteId, long actorUserId) {
        return complete(() -> {
            authorize(organizationId, actorUserId);
            requireActiveSite(organizationId, siteId);
            Audit.AuditStart start = Audit.startWithFirstScan(
                    entityManager.getReference(Organization.class, organizationId),
                    entityManager.getReference(Site.class, siteId),
                    entityManager.getReference(Usuario.class, actorUserId),
                    UtcMicros.now(clock));
            entityManager.persist(start.audit());
            entityManager.persist(start.firstScan());
            entityManager.flush();
            Scan first = start.firstScan();
            return new AuditCreation(start.audit().getId(), first.getId(), first.getAttemptNumber(), first.getStatus());
        });
    }

    /** Adds the next attempt after a FAILED or CANCELLED latest attempt, under the Audit lock. */
    public ScanOutcome retryScan(long organizationId, long auditId, long actorUserId) {
        return complete(() -> {
            authorize(organizationId, actorUserId);
            Audit audit = lockAudit(organizationId, auditId);
            authorize(organizationId, actorUserId);
            requireActiveSite(organizationId, audit.getSite().getId());
            int next = Scan.nextAttemptAfter(currentAttempts(organizationId, auditId));
            Scan scan = Scan.retry(audit, next, entityManager.getReference(Usuario.class, actorUserId), UtcMicros.now(clock));
            entityManager.persist(scan);
            entityManager.flush();
            return new ScanOutcome(auditId, scan.getId(), scan.getAttemptNumber(), scan.getStatus());
        });
    }

    /** Cancels a PENDING or RUNNING Scan under the Audit lock; allowed on ARCHIVED Sites. */
    public ScanOutcome cancelScan(long organizationId, long scanId, long actorUserId) {
        return complete(() -> {
            authorize(organizationId, actorUserId);
            List<Long> auditIds = entityManager.createQuery(
                            "select s.audit.id from Scan s where s.id = :scan and s.organization.id = :organization", Long.class)
                    .setParameter("scan", scanId)
                    .setParameter("organization", organizationId)
                    .getResultList();
            if (auditIds.size() != 1) {
                throw notFound();
            }
            long auditId = auditIds.get(0);
            lockAudit(organizationId, auditId);
            authorize(organizationId, actorUserId);
            List<Scan> scans = entityManager.createQuery(
                            "select s from Scan s where s.id = :scan and s.organization.id = :organization and s.audit.id = :audit", Scan.class)
                    .setParameter("scan", scanId)
                    .setParameter("organization", organizationId)
                    .setParameter("audit", auditId)
                    .getResultList();
            if (scans.size() != 1) {
                throw notFound();
            }
            Scan scan = scans.get(0);
            entityManager.refresh(scan); // Current committed state and version, even if a bound context cached it.
            scan.cancel(UtcMicros.now(clock));
            entityManager.flush();
            return new ScanOutcome(auditId, scan.getId(), scan.getAttemptNumber(), scan.getStatus());
        });
    }

    private <T> T complete(Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AuditScanService operations are complete transactions; call them outside any transaction");
        }
        T result = transactions.execute(status -> {
            if (entityManager.unwrap(Session.class).isDirty()) {
                throw new IllegalStateException("The thread-bound persistence context has unflushed changes; refusing to commit them");
            }
            return operation.get();
        });
        return Objects.requireNonNull(result, "operation result");
    }

    /** Owner ACTIVE membership of an active user, read as a scalar so no cached entity can decide it. */
    private void authorize(long organizationId, long actorUserId) {
        Long matches = entityManager.createQuery(
                        "select count(m) from OrganizationMembership m where m.organization.id = :organization "
                                + "and m.user.id = :user and m.role = :role and m.status = :status and m.user.activo = true", Long.class)
                .setParameter("organization", organizationId)
                .setParameter("user", actorUserId)
                .setParameter("role", MembershipRole.OWNER)
                .setParameter("status", MembershipStatus.ACTIVE)
                .getSingleResult();
        if (matches == null || matches != 1L) {
            throw notFound();
        }
    }

    private void requireActiveSite(long organizationId, long siteId) {
        List<SiteStatus> statuses = entityManager.createQuery(
                        "select s.status from Site s where s.id = :site and s.organization.id = :organization", SiteStatus.class)
                .setParameter("site", siteId)
                .setParameter("organization", organizationId)
                .getResultList();
        if (statuses.size() != 1) {
            throw notFound();
        }
        if (statuses.get(0) != SiteStatus.ACTIVE) {
            throw new IllegalStateException("An ARCHIVED Site accepts no new Audit or Scan");
        }
    }

    /** One tenant-scoped SELECT ... FOR UPDATE: another organization's row is never locked. */
    private Audit lockAudit(long organizationId, long auditId) {
        List<Audit> audits = entityManager.createQuery(
                        "select a from Audit a where a.id = :audit and a.organization.id = :organization", Audit.class)
                .setParameter("audit", auditId)
                .setParameter("organization", organizationId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (audits.size() != 1) {
            throw notFound();
        }
        return audits.get(0);
    }

    /** Committed attempts read after the lock with a scalar query, never from a loaded collection. */
    private List<Scan.Attempt> currentAttempts(long organizationId, long auditId) {
        return entityManager.createQuery(
                        "select s.attemptNumber, s.status from Scan s where s.audit.id = :audit and s.organization.id = :organization",
                        Object[].class)
                .setParameter("audit", auditId)
                .setParameter("organization", organizationId)
                .getResultList()
                .stream()
                .map(row -> new Scan.Attempt((Integer) row[0], (ScanStatus) row[1]))
                .toList();
    }

    /** Uniform answer for missing, foreign or unauthorized resources (future HTTP 404). */
    private static EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found");
    }
}
