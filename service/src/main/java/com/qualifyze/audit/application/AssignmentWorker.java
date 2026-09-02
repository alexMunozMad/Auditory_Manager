package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The single-writer assignment worker (ADR 0001, docs/05). One {@link #runOnce()} call claims a
 * batch of pending requests with {@code FOR UPDATE SKIP LOCKED} and assigns each — the claim and
 * every write it drives commit together (A8).
 *
 * <p>What triggers a tick — {@code LISTEN/NOTIFY} on a new request or a freed slot, with a periodic
 * sweep as the backstop (docs/05 §6) — is out of the slice: the concurrency test drives
 * {@link #runOnce()} directly from several threads (docs/06 §3).
 */
@Component
public class AssignmentWorker {

	/** Bounded so one tick's transaction, and the row locks it holds, stay short. */
	static final int BATCH_SIZE = 20;

	private final AuditRequestRepository requests;
	private final AssignmentService assignment;

	AssignmentWorker(AuditRequestRepository requests, AssignmentService assignment) {
		this.requests = requests;
		this.assignment = assignment;
	}

	/** Claim and assign one batch. Returns how many requests were claimed (0 when the queue is empty). */
	@Transactional
	public int runOnce() {
		List<AuditRequest> claimed = requests.claimPending(BATCH_SIZE);
		for (AuditRequest request : claimed) {
			assignment.assign(request);
		}
		return claimed.size();
	}
}
