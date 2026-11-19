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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.mail.SendPacing.Refusal;
import se.hirt.mcp.mail.SendPacing.SentRecord;
import se.hirt.mcp.mail.SendPacing.SentSummary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How SendPacing spaces and counts the messages of an account, on a clock that only moves when
 * told.
 */
class SendPacingTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
	private static final String ACCOUNT = "default";
	private static final PacingLimits PAUSE = new PacingLimits(120, 600, 0);

	/** A Sent folder holding what the test puts in it, and counting how often it is read. */
	private static final class FakeSent implements SendPacing.SentLog {
		volatile SentSummary summary = SentSummary.EMPTY;
		volatile MessagingException failure;
		volatile Instant countSince;
		volatile Instant latestSince;
		final AtomicInteger reads = new AtomicInteger();

		@Override
		public SentSummary read(Instant countSince, Instant latestSince) throws MessagingException {
			reads.incrementAndGet();
			this.countSince = countSince;
			this.latestSince = latestSince;
			if (failure != null) {
				throw failure;
			}
			return summary;
		}
	}

	private final MutableClock clock = new MutableClock(at("2026-11-19T10:00:00"), ZONE);
	private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
	/** The pauses the draw returns, in order; the minimum once empty. */
	private final Queue<Long> draws = new ConcurrentLinkedQueue<>();
	private final AtomicInteger drawCount = new AtomicInteger();
	private final FakeSent sent = new FakeSent();
	private final ExecutorService otherThread = Executors.newSingleThreadExecutor();
	/** Runs during the next sleep, before the clock moves: what happens while a call waits. */
	private volatile Runnable duringSleep = () -> {
	};
	private final SendPacing pacing = new SendPacing(clock, duration -> {
		sleeps.add(duration);
		var hook = duringSleep;
		duringSleep = () -> {
		};
		hook.run();
		clock.advance(duration);
	}, (min, max) -> {
		drawCount.incrementAndGet();
		var pause = draws.poll();
		return pause != null ? pause : min;
	});

	@AfterEach
	void stopOtherThread() {
		otherThread.shutdownNow();
	}

	private static Instant at(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant();
	}

	private static SentRecord record(Instant at, String subject) {
		return new SentRecord(at, "to@example.com", subject, "<" + subject.replace(' ', '-') + "@example.com>");
	}

	/** Takes the turn as a call starting now, sends, records the send and gives the turn back. */
	private Duration send(PacingLimits limits, String subject) throws Exception {
		try (var permit = pacing.acquire(ACCOUNT, limits, sent, clock.instant())) {
			var message = record(clock.instant(), subject);
			permit.sent(message.to(), message.subject(), message.messageId());
			return permit.waited();
		}
	}

	// ── Settings ─────────────────────────────────────────────────────────

	@Test
	void settingsAtZeroTurnPacingOff() throws Exception {
		assertFalse(PacingLimits.of(ACCOUNT, 0, 0, 0).on());
	}

	@Test
	void validSettingsAreAccepted() throws Exception {
		var both = PacingLimits.of(ACCOUNT, 120, 600, 80);
		assertTrue(both.pauseOn());
		assertTrue(both.limitOn());
		assertTrue(PacingLimits.of(ACCOUNT, 1, 1, 0).pauseOn());
		var limitOnly = PacingLimits.of(ACCOUNT, 0, 0, 80);
		assertFalse(limitOnly.pauseOn());
		assertTrue(limitOnly.limitOn());
	}

	@Test
	void invalidSettingsAreRefusedAndNamed() {
		int[][] invalid = {{-1, 600, 0}, {120, 600, -5}, {0, 600, 0}, {120, 0, 0}, {600, 120, 80}};
		for (int[] s : invalid) {
			var e = assertThrows(Refusal.class, () -> PacingLimits.of("my-work", s[0], s[1], s[2]));
			var message = e.getMessage();
			assertTrue(message.contains("Nothing can be sent from this account until"), message);
			assertTrue(message.contains("EMAIL_ACCOUNTS_MY_WORK_MIN_SEND_DELAY=" + s[0]), message);
			assertTrue(message.contains("EMAIL_ACCOUNTS_MY_WORK_MAX_SEND_DELAY=" + s[1]), message);
			assertTrue(message.contains("EMAIL_ACCOUNTS_MY_WORK_MAX_SENDS_PER_DAY=" + s[2]), message);
		}
		assertTrue(assertThrows(Refusal.class, () -> PacingLimits.of(ACCOUNT, 0, 600, 0)).getMessage()
				.contains("at least 1 second"));
		assertTrue(assertThrows(Refusal.class, () -> PacingLimits.of(ACCOUNT, 120, 0, 0)).getMessage()
				.contains("below the minimum"));
	}

	@Test
	void settingsReadAsTextTakeEmptyForZeroAndWholeNumbersAsTheyAre() throws Exception {
		assertEquals(new PacingLimits(0, 0, 0),
				PacingLimits.of(ACCOUNT, Optional.empty(), Optional.of(""), Optional.of("  ")));
		assertEquals(new PacingLimits(0, 0, 0), PacingLimits.of(ACCOUNT, Optional.of("${user_config.min_send_delay}"),
				Optional.of("${user_config.max_send_delay}"), Optional.empty()));
		assertEquals(new PacingLimits(120, 600, 80),
				PacingLimits.of(ACCOUNT, Optional.of("120"), Optional.of(" 600 "), Optional.of("80")));
	}

	@Test
	void settingsThatAreNotWholeNumbersAreRefusedAndNamed() {
		var decimal = assertThrows(Refusal.class,
				() -> PacingLimits.of("my-work", Optional.of("120.0"), Optional.of("600"), Optional.of("80")))
				.getMessage();
		assertEquals("The send pacing settings of account my-work are invalid: EMAIL_ACCOUNTS_MY_WORK_MIN_SEND_DELAY "
				+ "is \"120.0\", which is not a whole number. Nothing can be sent from this account until they are "
				+ "fixed in the extension settings or the environment and the server is restarted.", decimal);

		var two = assertThrows(Refusal.class,
				() -> PacingLimits.of("my-work", Optional.of("2 min"), Optional.of("600"), Optional.of("eighty")))
				.getMessage();
		assertTrue(two.contains("EMAIL_ACCOUNTS_MY_WORK_MIN_SEND_DELAY is \"2 min\" and "
				+ "EMAIL_ACCOUNTS_MY_WORK_MAX_SENDS_PER_DAY is \"eighty\", which are not whole numbers"), two);

		var negative = assertThrows(Refusal.class,
				() -> PacingLimits.of("my-work", Optional.of("-5"), Optional.of("600"), Optional.of("80")))
				.getMessage();
		assertTrue(negative.contains("no setting may be negative"), negative);
	}

	// ── Not paced ────────────────────────────────────────────────────────

	@Test
	void anAccountNotPacedTakesNoTurnReadsNothingAndWaitsNever() throws Exception {
		var off = PacingLimits.of(ACCOUNT, 0, 0, 0);
		var first = pacing.acquire(ACCOUNT, off, sent, clock.instant());
		var second = otherThread.submit(() -> pacing.acquire(ACCOUNT, off, sent, clock.instant())).get(5,
				TimeUnit.SECONDS);
		first.sent("to@example.com", "first", "<first@example.com>");
		first.close();
		second.close();
		assertEquals(Duration.ZERO, first.waited());
		assertEquals(0, sent.reads.get());
		assertEquals(0, drawCount.get());
		assertTrue(sleeps.isEmpty());
	}

	// ── Pause between messages ───────────────────────────────────────────

	@Test
	void theFirstMessageLeavesAtOnceAndTheNextOneWaitsForTheEndOfThePause() throws Exception {
		draws.add(300L);
		assertEquals(Duration.ZERO, send(PAUSE, "first"));
		clock.advance(Duration.ofSeconds(280));
		assertEquals(Duration.ofSeconds(20), send(PAUSE, "second"));
		assertEquals(List.of(Duration.ofSeconds(20)), sleeps);
	}

	@Test
	void aPauseOfUpToTheBoundIsWaitedInTheCall() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(300).minus(SendPacing.MAX_WAIT));
		assertEquals(SendPacing.MAX_WAIT, send(PAUSE, "second"));
	}

	@Test
	void tooEarlyIsRefusedWithTheNextSlotAndTheLastMessage() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(100));
		var message = assertThrows(Refusal.class, () -> send(PAUSE, "second")).getMessage();
		assertTrue(message.startsWith("Too early, nothing was sent"), message);
		assertTrue(message.contains("the next one may leave at 10:05:00, in 3 min 20 s"), message);
		assertTrue(message.contains("Last message sent at 10:00:00 to to@example.com, subject \"first\", "
				+ "Message-ID <first@example.com>: if that is the message about to be sent, it has already left"),
				message);
		assertTrue(message.contains("waitForSendSlot"), message);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void retryingDoesNotDrawThePauseAgain() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		for (int i = 0; i < 5; i++) {
			clock.advance(Duration.ofSeconds(10));
			assertThrows(Refusal.class, () -> send(PAUSE, "again"));
		}
		assertEquals(1, drawCount.get());
	}

	@Test
	void timeAlreadySpentInTheCallCountsAgainstTheBound() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(280));
		var tooLate = clock.instant().minusSeconds(26);
		assertThrows(Refusal.class, () -> pacing.acquire(ACCOUNT, PAUSE, sent, tooLate));
		try (var permit = pacing.acquire(ACCOUNT, PAUSE, sent, clock.instant().minusSeconds(25))) {
			assertEquals(Duration.ofSeconds(20), permit.waited());
		}
	}

	// ── Sent folder ──────────────────────────────────────────────────────

	@Test
	void theSentFolderIsReadFromMidnightOrFromTheLongestPauseIfEarlier() throws Exception {
		send(PAUSE, "morning");
		assertEquals(at("2026-11-19T00:00:00"), sent.countSince);
		assertEquals(at("2026-11-19T00:00:00"), sent.latestSince);
		clock.set(at("2026-11-20T00:05:00"));
		send(PAUSE, "after midnight");
		assertEquals(at("2026-11-20T00:00:00"), sent.countSince);
		assertEquals(at("2026-11-19T23:55:00"), sent.latestSince);
	}

	@Test
	void thePauseHoldsAfterARestartThroughTheSentFolder() throws Exception {
		sent.summary = new SentSummary(1, Optional.of(record(at("2026-11-19T09:59:00"), "before restart")));
		draws.add(300L);
		var message = assertThrows(Refusal.class, () -> send(PAUSE, "next")).getMessage();
		assertTrue(message.contains("may leave at 10:04:00"), message);
		assertTrue(message.contains("subject \"before restart\""), message);
	}

	@Test
	void ourOwnCopyInTheSentFolderDoesNotStartThePauseAgain() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		// The copy, filed a second later by the server's clock, carries the same Message-ID.
		sent.summary = new SentSummary(1, Optional.of(record(clock.instant().plusSeconds(1), "first")));
		clock.advance(Duration.ofSeconds(290));
		assertEquals(Duration.ofSeconds(10), send(PAUSE, "second"));
		assertEquals(2, drawCount.get());
	}

	@Test
	void aMessageSentFromAnotherMailClientStartsThePauseAgain() throws Exception {
		draws.add(120L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(60));
		sent.summary = new SentSummary(2, Optional.of(record(clock.instant(), "by hand")));
		draws.add(300L);
		var message = assertThrows(Refusal.class, () -> send(PAUSE, "second")).getMessage();
		assertTrue(message.contains("may leave at 10:06:00"), message);
		assertTrue(message.contains("subject \"by hand\""), message);
	}

	@Test
	void aShorterPauseAfterAMessageSentByHandDoesNotShortenTheOneDrawn() throws Exception {
		draws.add(600L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(60));
		sent.summary = new SentSummary(2, Optional.of(record(clock.instant(), "by hand")));
		draws.add(120L);
		var message = assertThrows(Refusal.class, () -> send(PAUSE, "second")).getMessage();
		assertTrue(message.contains("may leave at 10:10:00"), message);
	}

	@Test
	void anUnreadableSentFolderRefusesAndGivesTheTurnBack() throws Exception {
		var limit = new PacingLimits(0, 0, 80);
		sent.failure = new MessagingException("connection timed out");
		var message = assertThrows(Refusal.class, () -> send(limit, "first")).getMessage();
		assertTrue(message.startsWith("Nothing was sent: the Sent folder of account default could not be read"),
				message);
		assertTrue(message.contains("connection timed out"), message);
		sent.failure = null;
		// From another thread: the lock is reentrant, so only another thread proves it was released.
		assertEquals(Duration.ZERO, otherThread.submit(() -> send(limit, "after")).get(5, TimeUnit.SECONDS));
	}

	@Test
	void aSlowSentFolderRefusesRatherThanSendLate() throws Exception {
		SendPacing.SentLog slow = (countSince, latestSince) -> {
			clock.advance(SendPacing.MAX_WAIT.plusSeconds(1));
			return SentSummary.EMPTY;
		};
		var limit = new PacingLimits(0, 0, 80);
		var message = assertThrows(Refusal.class, () -> pacing.acquire(ACCOUNT, limit, slow, clock.instant()))
				.getMessage();
		assertTrue(message.contains("took too long"), message);
		assertEquals(Duration.ZERO, otherThread.submit(() -> send(limit, "after")).get(5, TimeUnit.SECONDS));
	}

	// ── Failed attempts ──────────────────────────────────────────────────

	@Test
	void aFailedAttemptStartsThePauseButDoesNotCountForTheDay() throws Exception {
		var limits = new PacingLimits(120, 600, 1);
		draws.add(300L);
		try (var permit = pacing.acquire(ACCOUNT, limits, sent, clock.instant())) {
			permit.attempted();
		}
		clock.advance(Duration.ofSeconds(100));
		var message = assertThrows(Refusal.class, () -> send(limits, "retry")).getMessage();
		assertTrue(message.contains("may leave at 10:05:00"), "the pause holds after a failed attempt: " + message);
		assertFalse(message.contains("Last message sent"), "a failed attempt is not a message sent: " + message);

		clock.advance(Duration.ofSeconds(200));
		assertEquals(Duration.ZERO, send(limits, "retry"), "the attempt did not use up the limit of 1 a day");
		clock.advance(Duration.ofHours(1));
		assertTrue(assertThrows(Refusal.class, () -> send(limits, "third")).getMessage()
				.contains("(1 sent since midnight)"));
	}

	// ── Daily limit ──────────────────────────────────────────────────────

	@Test
	void theDailyLimitCountsTheMessagesInTheSentFolder() throws Exception {
		var limit = new PacingLimits(0, 0, 3);
		sent.summary = new SentSummary(3, Optional.of(record(at("2026-11-19T09:50:00"), "third")));
		var message = assertThrows(Refusal.class, () -> send(limit, "fourth")).getMessage();
		assertTrue(message.contains("has reached its limit of 3 messages a day (3 sent since midnight)"), message);
		assertTrue(message.contains("may leave from 2026-11-20 00:00:00"), message);
		assertTrue(message.contains("subject \"third\""), message);
	}

	@Test
	void theDailyLimitCountsTheMessagesSentByThisServerWhenTheirCopyIsMissing() throws Exception {
		var limit = new PacingLimits(0, 0, 3);
		for (int i = 1; i <= 3; i++) {
			assertEquals(Duration.ZERO, send(limit, "message " + i));
		}
		var message = assertThrows(Refusal.class, () -> send(limit, "message 4")).getMessage();
		assertTrue(message.contains("(3 sent since midnight)"), message);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void theDailyLimitStartsAgainAtMidnight() throws Exception {
		var limit = new PacingLimits(0, 0, 1);
		clock.set(at("2026-11-19T23:59:00"));
		send(limit, "late");
		clock.advance(Duration.ofSeconds(30));
		assertThrows(Refusal.class, () -> send(limit, "too late"));
		clock.set(at("2026-11-20T00:00:30"));
		assertEquals(Duration.ZERO, send(limit, "next day"));
	}

	// ── Status and waiting for the next slot ─────────────────────────────

	@Test
	void theStatusTellsTheSettingsTheDayTheNextSlotAndTheLastMessage() throws Exception {
		var limits = new PacingLimits(120, 600, 80);
		draws.add(300L);
		send(limits, "first");
		clock.advance(Duration.ofSeconds(100));

		var status = pacing.status(ACCOUNT, limits, sent, clock.instant());

		assertEquals(1, status.sentToday());
		assertEquals(at("2026-11-19T10:05:00"), status.nextSlot());
		assertEquals("Account default paces its messages: a pause of 120 to 600 s between two messages, and at most "
				+ "80 messages a day. Sent today: 1 of 80. The next message may leave at 10:05:00, in 3 min 20 s. "
				+ "Last message sent at 10:00:00 to to@example.com, subject \"first\", Message-ID <first@example.com>: "
				+ "if that is the message about to be sent, it has already left, do not send it again.",
				pacing.describe(ACCOUNT, status));
		assertTrue(sleeps.isEmpty(), "the status never waits");
	}

	@Test
	void theStatusShowsTheSlotASendWouldGet() throws Exception {
		// After a restart, the latest message is found in the Sent folder: its pause is drawn once, for both.
		sent.summary = new SentSummary(1, Optional.of(record(at("2026-11-19T09:59:00"), "before restart")));
		draws.add(300L);
		var status = pacing.status(ACCOUNT, PAUSE, sent, clock.instant());
		var refusal = assertThrows(Refusal.class, () -> send(PAUSE, "next")).getMessage();

		assertEquals(at("2026-11-19T10:04:00"), status.nextSlot());
		assertTrue(refusal.contains("may leave at 10:04:00"), refusal);
		assertEquals(1, drawCount.get());
	}

	@Test
	void theStatusSaysWhenTheDailyLimitIsReached() throws Exception {
		var limits = new PacingLimits(0, 0, 2);
		sent.summary = new SentSummary(2, Optional.empty());

		var status = pacing.status(ACCOUNT, limits, sent, clock.instant());

		assertTrue(status.limitReached());
		assertEquals(
				"Account default paces its messages: at most 2 messages a day. Sent today: 2 of 2. The daily "
						+ "limit is reached: the next message may leave from 2026-11-20 00:00:00.",
				pacing.describe(ACCOUNT, status));
	}

	@Test
	void waitingForTheSlotLastsAtMostTheBoundAndSaysWhatRemains() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(100));
		var start = clock.instant();

		var text = pacing.waitForSlot(ACCOUNT, pacing.status(ACCOUNT, PAUSE, sent, start), start);

		assertEquals(List.of(SendPacing.MAX_WAIT), sleeps);
		assertTrue(text.startsWith("Waited 45 s. Account default paces its messages"), text);
		assertTrue(text.contains("The next message may leave at 10:05:00, in 2 min 35 s."), text);
		assertTrue(text.contains("subject \"first\""), text);
	}

	@Test
	void waitingForTheSlotStopsWhenItComes() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(290));
		var start = clock.instant();

		var text = pacing.waitForSlot(ACCOUNT, pacing.status(ACCOUNT, PAUSE, sent, start), start);

		assertEquals(List.of(Duration.ofSeconds(10)), sleeps);
		assertTrue(text.startsWith("Waited 10 s. "), text);
		assertTrue(text.contains("The next message may leave now."), text);
	}

	@Test
	void waitingForTheSlotDoesNotWaitForTheNextDay() throws Exception {
		var limits = new PacingLimits(120, 600, 1);
		send(limits, "only one");
		var start = clock.instant();

		var text = pacing.waitForSlot(ACCOUNT, pacing.status(ACCOUNT, limits, sent, start), start);

		assertTrue(sleeps.isEmpty());
		assertTrue(text.contains("The daily limit is reached"), text);
	}

	@Test
	void waitingForTheSlotLeavesTheTurnFreeForASend() throws Exception {
		draws.add(300L);
		send(PAUSE, "first");
		clock.advance(Duration.ofSeconds(100));
		var start = clock.instant();
		var status = pacing.status(ACCOUNT, PAUSE, sent, start);
		var during = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		// A send during the wait gets the turn at once: refused for the pause, not after waiting for a busy turn.
		duringSleep = () -> during.set(assertThrows(ExecutionException.class,
				() -> otherThread.submit(() -> send(PAUSE, "during")).get(5, TimeUnit.SECONDS)).getCause());

		pacing.waitForSlot(ACCOUNT, status, start);

		assertTrue(during.get().getMessage().startsWith("Too early"), during.get().getMessage());
		assertEquals(List.of(SendPacing.MAX_WAIT), sleeps);
	}

	// ── Turns ────────────────────────────────────────────────────────────

	@Test
	void twoSendsAtOnceTakeTurnsAndTheSecondOneSeesThePause() throws Exception {
		draws.add(300L);
		var first = pacing.acquire(ACCOUNT, PAUSE, sent, clock.instant());
		var second = otherThread.submit(() -> send(PAUSE, "second"));
		Thread.sleep(200);
		assertFalse(second.isDone(), "the second send must wait for the turn of the first");
		first.sent("to@example.com", "first", "<first@example.com>");
		first.close();
		var e = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
		assertInstanceOf(Refusal.class, e.getCause());
		assertTrue(e.getCause().getMessage().startsWith("Too early"), e.getCause().getMessage());
	}

	@Test
	void aTurnNotGivenBackInTimeIsRefused() throws Exception {
		var first = pacing.acquire(ACCOUNT, PAUSE, sent, clock.instant());
		try {
			var callStart = clock.instant().minus(SendPacing.MAX_WAIT).plusMillis(200);
			var second = otherThread.submit(() -> pacing.acquire(ACCOUNT, PAUSE, sent, callStart));
			var e = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
			assertTrue(e.getCause().getMessage().contains("another message from account default is being sent"),
					e.getCause().getMessage());
		} finally {
			first.close();
		}
	}

	@Test
	void durationsAreRoundedUpToTheSecond() {
		assertEquals("5 s", PacingText.duration(Duration.ofSeconds(5)));
		assertEquals("2 s", PacingText.duration(Duration.ofMillis(1500)));
		assertEquals("2 min", PacingText.duration(Duration.ofSeconds(120)));
		assertEquals("3 min 20 s", PacingText.duration(Duration.ofSeconds(200)));
	}
}
