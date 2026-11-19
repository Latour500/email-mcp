/*
 * Copyright (C) 2026 Latour500
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.mail;

import jakarta.mail.MessagingException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongBinaryOperator;

/**
 * Paces the messages sent by each account: a pause drawn at random between two sends, and a limit
 * per day. Both are off by default, and an account with neither is not paced at all: no lock, no
 * lookup in the Sent folder, no wait.
 * <p>
 * A send must stay well under the time a client waits for a tool call: Claude Desktop gave up after
 * 80 seconds when measured, without telling the server. So the account's turn, the lookup in the
 * Sent folder and the pause must all end within {@link #MAX_WAIT} of the start of the call;
 * otherwise nothing is sent, and the refusal says when the next message may leave. The pause is
 * drawn once after each send, never at each attempt, so retrying cannot shorten it.
 * <p>
 * The clock, the sleep and the random draw are injected so that the tests do not wait.
 */
final class SendPacing {

	/** Longest time a send call may spend before the message leaves: turn, lookup and pause. */
	static final Duration MAX_WAIT = Duration.ofSeconds(45);

	/** A message the account sent, as this server or the Sent folder knows it. */
	record SentRecord(Instant at, String to, String subject, String messageId) {
		boolean sameMessage(SentRecord other) {
			return messageId != null && messageId.equals(other.messageId);
		}
	}

	/**
	 * What the Sent folder holds: the number of messages filed since midnight, and the latest one.
	 */
	record SentSummary(int sinceMidnight, Optional<SentRecord> latest) {
		static final SentSummary EMPTY = new SentSummary(0, Optional.empty());
	}

	/** Reads an account's Sent folder. */
	@FunctionalInterface
	interface SentLog {
		/**
		 * @param countSince
		 *            the start of the day: the messages filed since then are counted
		 * @param latestSince
		 *            the latest message filed since then is returned
		 */
		SentSummary read(Instant countSince, Instant latestSince) throws MessagingException;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	/**
	 * A send that the pacing refused. Nothing was sent, and the message says why and what to do.
	 */
	static final class Refusal extends MessagingException {
		private static final long serialVersionUID = 1L;

		Refusal(String message) {
			super(message);
		}
	}

	/** The account's turn to send, held until closed. */
	interface Permit extends AutoCloseable {
		/** How long the call waited for the pause to end. */
		Duration waited();

		/** Records the message just sent: it counts for the day, and the next pause is drawn. */
		void sent(String to, String subject, String messageId);

		/**
		 * Records a send that failed after reaching the SMTP server. The message may have left all
		 * the same, for instance when the server's answer timed out, so the next pause is drawn; it
		 * does not count for the day.
		 */
		void attempted();

		@Override
		void close();
	}

	/** For an account that is not paced: holds nothing, records nothing. */
	private static final Permit NOT_PACED = new Permit() {
		public Duration waited() {
			return Duration.ZERO;
		}

		public void sent(String to, String subject, String messageId) {
		}

		public void attempted() {
		}

		public void close() {
		}
	};

	/** What this server knows of an account's sends. Every field is guarded by the lock. */
	private static final class AccountState {
		final ReentrantLock lock = new ReentrantLock(true);
		LocalDate day;
		int sentToday;
		SentRecord last;
		Instant nextSlot;
	}

	private final Clock clock;
	private final Sleeper sleeper;
	private final LongBinaryOperator drawer;
	private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();
	private final PacingText text;

	SendPacing() {
		this(Clock.systemDefaultZone(), Thread::sleep,
				(min, max) -> ThreadLocalRandom.current().nextLong(min, max + 1));
	}

	/**
	 * @param drawer
	 *            draws a pause in seconds between its two arguments, both included
	 */
	SendPacing(Clock clock, Sleeper sleeper, LongBinaryOperator drawer) {
		this.clock = clock;
		this.sleeper = sleeper;
		this.drawer = drawer;
		this.text = new PacingText(clock);
	}

	/** The time on the pacing clock: what a send call records as its start. */
	Instant now() {
		return clock.instant();
	}

	/**
	 * Takes the account's turn to send, then checks the daily limit and waits for the end of the
	 * pause. For an account that is not paced, returns at once a permit that holds nothing, without
	 * reading the Sent folder. Otherwise the permit holds the turn until closed: the caller sends,
	 * calls {@link Permit#sent}, then closes it.
	 *
	 * @param start
	 *            when the tool call began: the turn, the lookup and the pause must end within
	 *            {@link #MAX_WAIT} of it
	 * @throws Refusal
	 *             when the message must not leave now. Nothing was sent.
	 */
	Permit acquire(String account, PacingLimits limits, SentLog sentLog, Instant start) throws Refusal {
		if (!limits.on()) {
			return NOT_PACED;
		}
		var deadline = start.plus(MAX_WAIT);
		var state = turn(account, deadline, "Nothing was sent: another message from account " + account
				+ " is being sent right now. Try again in a minute.");
		try {
			return new Held(state, limits, prepare(account, limits, sentLog, state, deadline));
		} catch (Refusal | RuntimeException e) {
			state.lock.unlock();
			throw e;
		}
	}

	/**
	 * Takes the account's turn, waiting for it until the deadline at most; refuses with
	 * {@code busy} then.
	 */
	private AccountState turn(String account, Instant deadline, String busy) throws Refusal {
		var state = accounts.computeIfAbsent(account, a -> new AccountState());
		try {
			long millis = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
			if (!state.lock.tryLock(millis, TimeUnit.MILLISECONDS)) {
				throw new Refusal(busy);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Refusal("Interrupted while waiting for the turn of account " + account + ".");
		}
		return state;
	}

	/** Checks the daily limit and waits for the end of the pause; returns how long it waited. */
	private Duration prepare(String account, PacingLimits limits, SentLog sentLog, AccountState state, Instant deadline)
			throws Refusal {
		int sentToday;
		try {
			sentToday = refresh(limits, sentLog, state);
		} catch (MessagingException e) {
			throw new Refusal("Nothing was sent: the Sent folder of account " + account
					+ " could not be read, so the daily limit and the pause between messages cannot be checked ("
					+ e.getMessage() + ").");
		}
		if (clock.instant().isAfter(deadline)) {
			throw new Refusal("Nothing was sent: reading the Sent folder of account " + account + " took too long, "
					+ "and the message would have left too late for the result to reach the client. Try again in a "
					+ "minute.");
		}
		if (limits.limitOn() && sentToday >= limits.maxPerDay()) {
			throw new Refusal("Nothing was sent: account " + account + " has reached its limit of " + limits.maxPerDay()
					+ " messages a day (" + sentToday + " sent since midnight). The next message " + "may leave from "
					+ text.time(tomorrow()) + "." + text.lastSent(state.last));
		}
		if (!limits.pauseOn() || state.nextSlot == null) {
			return Duration.ZERO;
		}
		var wait = Duration.between(clock.instant(), state.nextSlot);
		if (!wait.isPositive()) {
			return Duration.ZERO;
		}
		if (state.nextSlot.isAfter(deadline)) {
			throw new Refusal("Too early, nothing was sent: account " + account + " leaves a pause between "
					+ "messages, and the next one may leave at " + text.time(state.nextSlot) + ", in "
					+ PacingText.duration(wait) + "." + text.lastSent(state.last)
					+ " Call waitForSendSlot until that time has come, then send again.");
		}
		try {
			sleeper.sleep(wait);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Refusal("Nothing was sent: interrupted during the pause of account " + account + ".");
		}
		return wait;
	}

	/**
	 * Reads the Sent folder and takes in what it says, as before a send. Returns the number of
	 * messages sent today: the larger of the Sent folder's count and this server's.
	 */
	private int refresh(PacingLimits limits, SentLog sentLog, AccountState state) throws MessagingException {
		var now = clock.instant();
		var today = LocalDate.ofInstant(now, clock.getZone());
		var midnight = today.atStartOfDay(clock.getZone()).toInstant();
		var pauseStart = now.minusSeconds(limits.maxDelay());
		var latestSince = limits.pauseOn() && pauseStart.isBefore(midnight) ? pauseStart : midnight;
		var filed = sentLog.read(midnight, latestSince);
		if (!today.equals(state.day)) {
			state.day = today;
			state.sentToday = 0;
		}
		filed.latest().ifPresent(latest -> adopt(state, limits, latest));
		return Math.max(filed.sinceMidnight(), state.sentToday);
	}

	private Instant tomorrow() {
		return LocalDate.now(clock).plusDays(1).atStartOfDay(clock.getZone()).toInstant();
	}

	// ── Status, for getSendLimits and waitForSendSlot ───────────────────

	/**
	 * Where the pacing of an account stands.
	 *
	 * @param nextSlot
	 *            when the next message may leave, or {@code null} if it may leave now
	 * @param last
	 *            the last message sent, or {@code null} if none is known
	 */
	record Status(PacingLimits limits, int sentToday, boolean limitReached, Instant nextSlot, SentRecord last) {
	}

	/**
	 * Where the pacing of a paced account stands, with the Sent folder read as before a send, so
	 * that the next slot shown is the one a send would get. Sends nothing and waits for no pause.
	 *
	 * @param start
	 *            when the tool call began: a send in progress is waited for until {@link #MAX_WAIT}
	 *            after it
	 */
	Status status(String account, PacingLimits limits, SentLog sentLog, Instant start) throws MessagingException {
		var state = turn(account, start.plus(MAX_WAIT),
				"A message from account " + account + " is being sent right now. Try again in a minute.");
		try {
			int sentToday;
			try {
				sentToday = refresh(limits, sentLog, state);
			} catch (MessagingException e) {
				throw new MessagingException(
						"the Sent folder of account " + account + " could not be read, so the "
								+ "daily limit and the pause between messages cannot be shown (" + e.getMessage() + ")",
						e);
			}
			return statusOf(state, limits, sentToday);
		} finally {
			state.lock.unlock();
		}
	}

	/** Builds the status from what this server knows; the caller holds the account's turn. */
	private Status statusOf(AccountState state, PacingLimits limits, int sentToday) {
		var now = clock.instant();
		Instant next = limits.pauseOn() && state.nextSlot != null && state.nextSlot.isAfter(now) ? state.nextSlot
				: null;
		boolean limitReached = limits.limitOn() && sentToday >= limits.maxPerDay();
		if (limitReached) {
			var tomorrow = tomorrow();
			next = next == null || tomorrow.isAfter(next) ? tomorrow : next;
		}
		return new Status(limits, sentToday, limitReached, next, state.last);
	}

	/**
	 * Waits until the next message of a paced account may leave, but never past {@link #MAX_WAIT}
	 * after the start of the call, and without holding the account's turn, so that a send may go
	 * meanwhile. Never sends anything, and does not wait when the daily limit is reached.
	 *
	 * @param before
	 *            the status read at the start of the call
	 * @return what to tell the client: how long it waited, then the status after the wait
	 */
	String waitForSlot(String account, Status before, Instant start) throws Refusal {
		var waited = Duration.ZERO;
		if (before.nextSlot() != null && !before.limitReached()) {
			var deadline = start.plus(MAX_WAIT);
			var until = before.nextSlot().isBefore(deadline) ? before.nextSlot() : deadline;
			var wait = Duration.between(clock.instant(), until);
			if (wait.isPositive()) {
				try {
					sleeper.sleep(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new Refusal("Interrupted while waiting for the next slot of account " + account + ".");
				}
				waited = wait;
			}
		}
		var after = before;
		var state = accounts.get(account);
		// A send may have gone during the wait: what it changed is read back, unless one is going right now.
		if (state != null && state.lock.tryLock()) {
			try {
				after = statusOf(state, before.limits(), Math.max(before.sentToday(), state.sentToday));
			} finally {
				state.lock.unlock();
			}
		}
		var prefix = waited.isPositive() ? "Waited " + PacingText.duration(waited) + ". " : "";
		return prefix + text.describe(account, after);
	}

	/** The status of a paced account, as getSendLimits tells it. */
	String describe(String account, Status status) {
		return text.describe(account, status);
	}

	/**
	 * Takes in a send found in the Sent folder that this server did not make, or made before a
	 * restart, and draws its pause once. The pause already drawn is kept if it ends later.
	 */
	private void adopt(AccountState state, PacingLimits limits, SentRecord latest) {
		if (state.last != null && (latest.sameMessage(state.last) || !latest.at().isAfter(state.last.at()))) {
			return;
		}
		state.last = latest;
		if (limits.pauseOn()) {
			var slot = latest.at().plusSeconds(draw(limits));
			if (state.nextSlot == null || slot.isAfter(state.nextSlot)) {
				state.nextSlot = slot;
			}
		}
	}

	private long draw(PacingLimits limits) {
		return drawer.applyAsLong(limits.minDelay(), limits.maxDelay());
	}

	/** The turn of a paced account. */
	private final class Held implements Permit {
		private final AccountState state;
		private final PacingLimits limits;
		private final Duration waited;
		private boolean open = true;

		Held(AccountState state, PacingLimits limits, Duration waited) {
			this.state = state;
			this.limits = limits;
			this.waited = waited;
		}

		public Duration waited() {
			return waited;
		}

		public void sent(String to, String subject, String messageId) {
			var now = startPause();
			var day = LocalDate.ofInstant(now, clock.getZone());
			if (!day.equals(state.day)) {
				state.day = day;
				state.sentToday = 0;
			}
			state.sentToday++;
			state.last = new SentRecord(now, to, subject, messageId);
		}

		public void attempted() {
			startPause();
		}

		/** Draws the pause before the next message, from now; returns now. */
		private Instant startPause() {
			if (!open) {
				throw new IllegalStateException("send recorded after the turn was given back");
			}
			var now = clock.instant();
			if (limits.pauseOn()) {
				state.nextSlot = now.plusSeconds(draw(limits));
			}
			return now;
		}

		public void close() {
			if (open) {
				open = false;
				state.lock.unlock();
			}
		}
	}
}
