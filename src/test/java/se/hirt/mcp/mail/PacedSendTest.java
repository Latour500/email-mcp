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

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import se.hirt.mcp.mail.SendPacing.Refusal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paced sends against in-memory GreenMail IMAP and SMTP servers: the Sent folder read over the
 * connection of the call, the pause and the daily limit, the cancellation checked before the
 * message leaves, and a connection the server closes. The pacing runs on a clock that moves only
 * when told, so no test waits for a pause.
 */
class PacedSendTest {

	private static final String USER = "user@localhost";
	private static final String PASSWORD = "secret";
	private static final String ACCOUNT = "test";
	private static final int IMAP = ServerSetupTest.IMAP.getPort();
	private static final int SMTP = ServerSetupTest.SMTP.getPort();
	private static final PacingLimits OFF = new PacingLimits(0, 0, 0);
	private static final PacingLimits PAUSE = new PacingLimits(120, 600, 0);

	@RegisterExtension
	static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
			.withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, USER, PASSWORD));

	private EmailService service;
	private MutableClock clock;
	/** The pauses the draw returns, in order; the minimum once empty. */
	private final Queue<Long> draws = new ConcurrentLinkedQueue<>();
	private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
	/** Runs during each pause, before the clock moves: what happens while the call waits. */
	private Runnable duringPause = () -> {
	};

	@BeforeEach
	void setUp() throws Exception {
		var user = greenMail.setUser(USER, USER, PASSWORD);
		for (var subject : List.of("A", "B", "C")) {
			user.deliver(GreenMailUtil.createTextEmail(USER, "sender@example.com", subject, "body " + subject,
					ServerSetupTest.IMAP));
		}
		// Real time to the second, so that the copies the server files and the pacing agree on the day.
		clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault());
		service = service(IMAP, SMTP, 60, OFF, Optional.empty());
	}

	private EmailService service(
		int imapPort, int smtpPort, int networkTimeout, PacingLimits limits, Optional<String> sentFolder) {
		return service(imapPort, smtpPort, networkTimeout, List.of(String.valueOf(limits.minDelay()),
				String.valueOf(limits.maxDelay()), String.valueOf(limits.maxPerDay())), sentFolder);
	}

	/**
	 * @param pacing
	 *            the three pacing settings as the environment gives them, as text; {@code null} for
	 *            one that is not set
	 */
	private EmailService service(
		int imapPort, int smtpPort, int networkTimeout, List<String> pacing, Optional<String> sentFolder) {
		var s = new EmailService();
		s.config = config(imapPort, smtpPort, networkTimeout, pacing, sentFolder);
		s.pacing = new SendPacing(clock, duration -> {
			sleeps.add(duration);
			duringPause.run();
			clock.advance(duration);
		}, (min, max) -> {
			var pause = draws.poll();
			return pause != null ? pause : min;
		});
		return s;
	}

	// ── Reading the Sent folder ─────────────────────────────────────────

	@Test
	void pacedReadCountsTheDayAndFindsTheLatestMessage() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		fileAt("Sent", "Two days ago", at("2026-01-08T10:00:00"));
		fileAt("Sent", "Last night", at("2026-01-09T23:30:00"));
		fileAt("Sent", "Nine", at("2026-01-10T09:00:00"));
		fileAt("Sent", "Eight", at("2026-01-10T08:00:00"));

		try (var imap = service.pacedImap(ACCOUNT)) {
			var summary = imap.read(at("2026-01-10T00:00:00"), at("2026-01-09T23:00:00"));

			assertEquals(2, summary.sinceMidnight());
			var latest = summary.latest().orElseThrow();
			assertEquals("Nine", latest.subject());
			assertEquals(at("2026-01-10T09:00:00"), latest.at());
			assertEquals("Jane Doe <jane@example.com>", latest.to());
			assertEquals(messageId("Sent", "Nine"), latest.messageId());
		}
	}

	@Test
	void pacedReadFindsTheLatestMessageBeforeMidnightOnlyWhenThePauseReachesBack() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		fileAt("Sent", "Last night", at("2026-01-09T23:58:00"));

		try (var imap = service.pacedImap(ACCOUNT)) {
			var reachingBack = imap.read(at("2026-01-10T00:00:00"), at("2026-01-09T23:55:00"));
			assertEquals(0, reachingBack.sinceMidnight());
			assertEquals("Last night", reachingBack.latest().orElseThrow().subject());
			assertTrue(imap.read(at("2026-01-10T00:00:00"), at("2026-01-10T00:00:00")).latest().isEmpty());
		}
	}

	@Test
	void pacedReadOfAnEmptySentFolder() throws Exception {
		service.createFolder(ACCOUNT, "Sent");

		try (var imap = service.pacedImap(ACCOUNT)) {
			assertEquals(SendPacing.SentSummary.EMPTY, imap.read(at("2026-01-10T00:00:00"), at("2026-01-10T00:00:00")));
		}
	}

	@Test
	void pacedReadFailsWithoutASentFolder() throws Exception {
		try (var imap = service.pacedImap(ACCOUNT)) {
			var e = assertThrows(MessagingException.class,
					() -> imap.read(at("2026-01-10T00:00:00"), at("2026-01-10T00:00:00")));
			assertTrue(e.getMessage().startsWith("no Sent folder found"), e.getMessage());
		}
		var misconfigured = service(IMAP, SMTP, 60, OFF, Optional.of("Missing"));
		try (var imap = misconfigured.pacedImap(ACCOUNT)) {
			var e = assertThrows(MessagingException.class,
					() -> imap.read(at("2026-01-10T00:00:00"), at("2026-01-10T00:00:00")));
			assertTrue(e.getMessage().contains("'Missing' does not exist"), e.getMessage());
		}
	}

	@Test
	void theCopyOfAMessageSentByThisServerIsFoundWithItsMessageId() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		var before = Instant.now().minusSeconds(5);
		service.sendEmail(ACCOUNT, "someone@example.com", null, null, "Paced copy", "hello",
				EmailService.NOT_CANCELLED);

		try (var imap = service.pacedImap(ACCOUNT)) {
			var latest = imap.read(before, before).latest().orElseThrow();
			assertEquals("Paced copy", latest.subject());
			assertEquals("someone@example.com", latest.to());
			assertEquals(messageId("Sent", "Paced copy"), latest.messageId(),
					"the pacing recognizes its own copy by the Message-ID of the message sent");
		}
	}

	@Test
	void messagesDeletedButNotYetExpungedDoNotCount() throws Exception {
		// A mail client that deletes a message often only flags it \Deleted, and expunges later.
		service.createFolder(ACCOUNT, "Sent");
		fileAt("Sent", "Eight", at("2026-01-10T08:00:00"));
		fileAt("Sent", "Nine", at("2026-01-10T09:00:00"));
		fileAt("Sent", "Deleted later", at("2026-01-10T09:30:00"));
		flagDeleted("Sent", "Deleted later");

		try (var imap = service.pacedImap(ACCOUNT)) {
			var summary = imap.read(at("2026-01-10T00:00:00"), at("2026-01-10T00:00:00"));

			assertEquals(2, summary.sinceMidnight(), "the message flagged \\Deleted is not counted");
			assertEquals("Nine", summary.latest().orElseThrow().subject(), "nor taken as the latest");
		}
	}

	@Test
	void theCopyIsNeverFiledWithADateWrittenInTheWrongZone() throws Exception {
		// In the native image, the formatter that writes the date of an appended message is created at
		// build time, in the build machine's zone (UTC), while the computer runs in another one. This puts
		// the formatter in a zone other than the default one, as on a computer outside UTC, during the send.
		// GreenMail, in the same JVM, writes the dates it stores with that same formatter, so what it would
		// read back proves nothing: the APPEND command the client sends is checked instead.
		var field = Class.forName("org.eclipse.angus.mail.imap.protocol.INTERNALDATE").getDeclaredField("df");
		field.setAccessible(true);
		var formatter = (SimpleDateFormat) field.get(null);
		var formatterZone = formatter.getTimeZone();
		var defaultIsUtc = TimeZone.getDefault().getOffset(System.currentTimeMillis()) == 0;
		service.createFolder(ACCOUNT, "Sent");
		try (var proxy = new CuttableProxy(IMAP)) {
			var proxied = service(proxy.port(), SMTP, 60, OFF, Optional.empty());
			var before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
			EmailService.SentCopy copy;
			try {
				formatter.setTimeZone(TimeZone.getTimeZone(defaultIsUtc ? "Europe/Berlin" : "UTC"));
				copy = proxied.sendEmail(ACCOUNT, "someone@example.com", null, null, "Zoned copy", "hello",
						EmailService.NOT_CANCELLED);
			} finally {
				formatter.setTimeZone(formatterZone);
			}
			var after = Instant.now();

			assertTrue(copy.saved(), copy.report());
			var append = proxy.sent().lines().filter(line -> line.contains(" APPEND ")).findFirst().orElseThrow();
			var date = java.util.regex.Pattern
					.compile("\"(\\d{1,2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4})\"").matcher(append);
			if (date.find()) {
				var filed = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", java.util.Locale.US).parse(date.group(1))
						.toInstant();
				assertFalse(filed.isBefore(before) || filed.isAfter(after.plusSeconds(1)),
						"the copy is filed with the date " + date.group(1) + " (" + filed + "), sent between " + before
								+ " and " + after + ": " + append);
			}
			// Without a date, the server records the time it files the copy.
		}
	}

	@Test
	void theDailyLimitAndThePauseCountWhatTheSentFolderHolds() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		fileAt("Sent", "Eight", at("2026-01-10T08:00:00"));
		fileAt("Sent", "Nine fifty-nine", at("2026-01-10T09:59:00"));
		var now = at("2026-01-10T10:00:00");
		// A fresh SendPacing for each, as after a restart: everything it knows comes from the Sent folder.
		Supplier<SendPacing> restarted = () -> new SendPacing(Clock.fixed(now, ZoneId.systemDefault()),
				d -> fail("no wait expected"), (min, max) -> min);

		try (var imap = service.pacedImap(ACCOUNT)) {
			var limit = assertThrows(Refusal.class,
					() -> restarted.get().acquire(ACCOUNT, new PacingLimits(0, 0, 2), imap, now)).getMessage();
			assertTrue(limit.contains("limit of 2 messages a day (2 sent since midnight)"), limit);

			var pause = assertThrows(Refusal.class, () -> restarted.get().acquire(ACCOUNT, PAUSE, imap, now))
					.getMessage();
			assertTrue(pause.contains("may leave at 10:01:00"), pause);
			assertTrue(pause.contains("subject \"Nine fifty-nine\""), pause);
		}
	}

	// ── The connection of the call ──────────────────────────────────────

	@Test
	void thePacedConnectionOpensOnlyWhenUsedAndClosesWithTheCall() throws Exception {
		var opened = new AtomicInteger();
		PacedImapConnection.MailSupplier<Store> opener = () -> {
			opened.incrementAndGet();
			return connect(IMAP);
		};
		new PacedImapConnection(opener, () -> "INBOX").close();
		assertEquals(0, opened.get(), "a connection never used is never opened");

		var used = new PacedImapConnection(opener, () -> "INBOX");
		assertEquals(3, used.read(at("2000-01-01T00:00:00"), at("2000-01-01T00:00:00")).sinceMidnight());
		var store = used.store();
		assertSame(store, used.store(), "one connection serves the whole call");
		used.close();
		assertEquals(1, opened.get());
		assertFalse(store.isConnected());
	}

	@Test
	void aConnectionClosedByTheServerIsOpenedAgainOncePerCall() throws Exception {
		try (var proxy = new CuttableProxy(IMAP)) {
			var opened = new AtomicInteger();
			var imap = new PacedImapConnection(() -> {
				opened.incrementAndGet();
				return connect(proxy.port());
			}, () -> "INBOX");
			assertEquals(3, imap.read(at("2000-01-01T00:00:00"), at("2000-01-01T00:00:00")).sinceMidnight());

			proxy.cut();
			assertEquals(3, imap.read(at("2000-01-01T00:00:00"), at("2000-01-01T00:00:00")).sinceMidnight(),
					"read again over a new connection");
			assertEquals(2, opened.get());

			proxy.cut();
			assertThrows(MessagingException.class,
					() -> imap.read(at("2000-01-01T00:00:00"), at("2000-01-01T00:00:00")));
			assertEquals(2, opened.get(), "opened again once per call, not twice");
			imap.close();
		}
	}

	@Test
	void pacedTimeoutsAreShortEvenWhenTheNetworkTimeoutIsLongerOrOff() {
		assertEquals(10, EmailService.pacedTimeout(60, EmailService.PACED_IMAP_TIMEOUT));
		assertEquals(10, EmailService.pacedTimeout(0, EmailService.PACED_IMAP_TIMEOUT));
		assertEquals(5, EmailService.pacedTimeout(5, EmailService.PACED_IMAP_TIMEOUT));
		assertEquals(15, EmailService.pacedTimeout(60, EmailService.PACED_SMTP_TIMEOUT));
		assertEquals(15, EmailService.pacedTimeout(0, EmailService.PACED_SMTP_TIMEOUT));
	}

	@Test
	void thePacedConnectionGivesUpOnASilentServerEvenWithTheNetworkTimeoutOff() throws Exception {
		// A listening socket that is never accepted: the IMAP greeting never comes, and the shared
		// timeout is off, so only the paced one can fire.
		try (var silentServer = new ServerSocket(0)) {
			var silent = service(silentServer.getLocalPort(), SMTP, 0, OFF, Optional.empty());

			long start = System.nanoTime();
			try (var imap = silent.pacedImap(ACCOUNT)) {
				assertThrows(MessagingException.class, imap::store);
			}
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

			assertTrue(elapsedMillis >= 9_000 && elapsedMillis < 20_000,
					"expected to give up after about 10 s, took " + elapsedMillis + " ms");
		}
	}

	// ── Sends that are not paced ────────────────────────────────────────

	@Test
	void aSendThatIsNotPacedLeavesAtOnceAndReportsItsMessageId() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service.pacing = new SendPacing(clock, d -> fail("no pause for an account not paced"),
				(min, max) -> fail("no draw for an account not paced"));

		var copy = service.sendEmail(ACCOUNT, USER, null, null, "Not paced", "hello", EmailService.NOT_CANCELLED);

		assertEquals(messageId("INBOX", "Not paced"), copy.messageId());
		assertEquals(Duration.ZERO, copy.waited());
		assertEquals("Message-ID: " + copy.messageId() + ". Copy saved to Sent.", copy.report());
	}

	@Test
	void aCancelledCallSendsNothingEvenWhenNotPaced() throws Exception {
		var e = assertThrows(MessagingException.class,
				() -> service.sendEmail(ACCOUNT, USER, null, null, "Cancelled", "hello", () -> true));

		assertEquals("Nothing was sent: the client cancelled the call.", e.getMessage());
		assertFalse(subjectsIn("INBOX").contains("Cancelled"));
	}

	// ── Paced sends ─────────────────────────────────────────────────────

	@Test
	void aPacedSendWaitsForTheEndOfThePauseAndSaysSo() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, PAUSE, Optional.empty());
		draws.add(150L);

		var first = service.sendEmail(ACCOUNT, USER, null, null, "First", "hello", EmailService.NOT_CANCELLED);
		clock.advance(Duration.ofSeconds(120));
		var second = service.sendEmail(ACCOUNT, USER, null, null, "Second", "hello", EmailService.NOT_CANCELLED);

		assertEquals(Duration.ZERO, first.waited());
		assertEquals(Duration.ofSeconds(30), second.waited());
		assertEquals(List.of(Duration.ofSeconds(30)), sleeps);
		assertEquals("Message-ID: " + second.messageId() + ". Waited 30 s for the pause between messages. "
				+ "Copy saved to Sent.", second.report());
		assertEquals(List.of("First", "Second"), subjectsIn("Sent"));
		assertEquals(messageId("INBOX", "Second"), second.messageId());
		assertEquals(messageId("Sent", "Second"), second.messageId());
	}

	@Test
	void aPacedSendTooEarlySendsNothingAndNamesTheLastMessage() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, PAUSE, Optional.empty());
		draws.add(300L);

		var first = service.sendEmail(ACCOUNT, USER, null, null, "First", "hello", EmailService.NOT_CANCELLED);
		clock.advance(Duration.ofSeconds(10));
		var refusal = assertThrows(Refusal.class,
				() -> service.sendEmail(ACCOUNT, USER, null, null, "Second", "hello", EmailService.NOT_CANCELLED))
				.getMessage();

		assertTrue(refusal.startsWith("Too early, nothing was sent"), refusal);
		assertTrue(refusal.contains("subject \"First\", Message-ID " + first.messageId()), refusal);
		assertFalse(subjectsIn("INBOX").contains("Second"));
		assertEquals(List.of("First"), subjectsIn("Sent"));
	}

	@Test
	void aCallCancelledDuringThePauseSendsNothingAndKeepsThePause() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, PAUSE, Optional.empty());
		draws.add(150L);
		var cancelled = new AtomicBoolean();
		duringPause = () -> cancelled.set(true);

		service.sendEmail(ACCOUNT, USER, null, null, "First", "hello", EmailService.NOT_CANCELLED);
		clock.advance(Duration.ofSeconds(120));
		var e = assertThrows(MessagingException.class,
				() -> service.sendEmail(ACCOUNT, USER, null, null, "Second", "hello", cancelled::get));

		assertEquals("Nothing was sent: the client cancelled the call.", e.getMessage());
		assertFalse(subjectsIn("INBOX").contains("Second"));
		duringPause = () -> fail("the pause is over, and no new one was drawn by the cancelled call");
		var third = service.sendEmail(ACCOUNT, USER, null, null, "Third", "hello", EmailService.NOT_CANCELLED);
		assertEquals(Duration.ZERO, third.waited());
	}

	@Test
	void aConnectionClosedByTheServerDuringThePauseOrBetweenSendsLosesNoCopy() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		try (var proxy = new CuttableProxy(IMAP)) {
			service = service(proxy.port(), SMTP, 60, PAUSE, Optional.empty());
			draws.add(150L);
			draws.add(150L);

			service.sendEmail(ACCOUNT, USER, null, null, "First", "hello", EmailService.NOT_CANCELLED);
			// Between two sends: every connection dropped, the shared one included.
			proxy.cut();
			clock.advance(Duration.ofSeconds(150));
			var second = service.sendEmail(ACCOUNT, USER, null, null, "Second", "hello", EmailService.NOT_CANCELLED);
			assertTrue(second.saved(), second.report());

			// During the pause of the third send: its connection, opened to read the Sent folder, is dropped.
			clock.advance(Duration.ofSeconds(120));
			duringPause = proxy::cut;
			var third = service.sendEmail(ACCOUNT, USER, null, null, "Third", "hello", EmailService.NOT_CANCELLED);
			assertEquals(Duration.ofSeconds(30), third.waited());
			assertTrue(third.saved(), "the copy is saved over a connection opened again: " + third.report());
		}
		assertEquals(List.of("First", "Second", "Third"), subjectsIn("Sent"));
	}

	@Test
	void aSendFailingAfterReachingTheServerStartsThePauseButDoesNotCount() throws Exception {
		// The SMTP server answers the greeting, then goes silent: the send times out. The shared
		// timeout is off, so the paced SMTP timeout of 15 s is what ends it.
		service.createFolder(ACCOUNT, "Sent");
		var limits = new PacingLimits(120, 600, 1);
		draws.add(300L);
		try (var silentSmtp = new SilentSmtpServer()) {
			service = service(IMAP, silentSmtp.port(), 0, limits, Optional.empty());

			long start = System.nanoTime();
			var e = assertThrows(MessagingException.class,
					() -> service.sendEmail(ACCOUNT, USER, null, null, "Lost?", "hello", EmailService.NOT_CANCELLED));
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

			assertTrue(elapsedMillis >= 14_000 && elapsedMillis < 30_000,
					"expected to give up after about 15 s, took " + elapsedMillis + " ms");
			assertTrue(e.getMessage().contains("the message may have left all the same: do not send it again"),
					e.getMessage());
		}
		assertEquals(List.of(), subjectsIn("Sent"), "no copy of a message that may not have left");

		var pacing = service.pacing;
		service = service(IMAP, SMTP, 60, limits, Optional.empty());
		service.pacing = pacing;
		var tooEarly = assertThrows(Refusal.class,
				() -> service.sendEmail(ACCOUNT, USER, null, null, "Again", "hello", EmailService.NOT_CANCELLED))
				.getMessage();
		assertTrue(tooEarly.startsWith("Too early"), "the failed attempt started the pause: " + tooEarly);

		clock.advance(Duration.ofSeconds(300));
		service.sendEmail(ACCOUNT, USER, null, null, "Again", "hello", EmailService.NOT_CANCELLED);
		clock.advance(Duration.ofSeconds(200));
		var limit = assertThrows(Refusal.class,
				() -> service.sendEmail(ACCOUNT, USER, null, null, "Third", "hello", EmailService.NOT_CANCELLED))
				.getMessage();
		assertTrue(limit.contains("(1 sent since midnight)"), "only the message that left counts: " + limit);
	}

	@Test
	void aSendThatCannotReachTheServerStartsNoPause() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		int closedPort;
		try (var socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		var unreachable = service(IMAP, closedPort, 60, PAUSE, Optional.empty());
		var pacing = unreachable.pacing;
		assertThrows(MessagingException.class,
				() -> unreachable.sendEmail(ACCOUNT, USER, null, null, "Nowhere", "hello", EmailService.NOT_CANCELLED));

		service = service(IMAP, SMTP, 60, PAUSE, Optional.empty());
		service.pacing = pacing;
		var copy = service.sendEmail(ACCOUNT, USER, null, null, "Somewhere", "hello", EmailService.NOT_CANCELLED);
		assertEquals(Duration.ZERO, copy.waited(), "nothing could have left, so no pause was drawn");
	}

	@Test
	void pacedRepliesAndForwardsAreSavedOverTheConnectionOfTheCall() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, new PacingLimits(0, 0, 80), Optional.empty());

		var reply = service.replyEmail(ACCOUNT, "INBOX", uidOf("INBOX", "A"), "reply body", false, null, null,
				EmailService.NOT_CANCELLED);
		var forward = service.forwardEmail(ACCOUNT, "INBOX", uidOf("INBOX", "B"), USER, "see below",
				EmailService.NOT_CANCELLED);
		service.sendEmail(ACCOUNT, "someone@example.com", null, null, "Follow-up", "first", EmailService.NOT_CANCELLED);
		var replyInSent = service.replyEmail(ACCOUNT, "Sent", uidOf("Sent", "Follow-up"), "second", false, null, null,
				EmailService.NOT_CANCELLED);

		assertTrue(reply.saved(), reply.report());
		assertTrue(forward.saved(), forward.report());
		assertTrue(replyInSent.saved(), replyInSent.report());
		assertEquals(messageId("Sent", "Re: A"), reply.messageId());
		assertEquals(messageId("Sent", "Fwd: B"), forward.messageId());
		assertTrue(source("Sent", "Fwd: B").contains("body B"),
				"the copy of the forward carries the original, read while its folder was still open");
		assertEquals(List.of("Follow-up", "Fwd: B", "Re: A", "Re: Follow-up"), subjectsIn("Sent"));
	}

	// ── The send tools ──────────────────────────────────────────────────

	@Test
	void theSendToolChecksTheClientsCancellation() throws Exception {
		var tools = new EmailTools();
		tools.emailService = service;
		tools.allowSending = true;
		Cancellation requested = () -> new Cancellation.Result(true, Optional.of("timeout"));

		var e = assertThrows(ToolCallException.class,
				() -> tools.sendEmail(ACCOUNT, USER, "", "", "Tool cancelled", "hello", requested));

		assertTrue(e.getMessage().contains("Nothing was sent: the client cancelled the call."), e.getMessage());
		assertFalse(subjectsIn("INBOX").contains("Tool cancelled"));
	}

	@Test
	void anEmptiedPacingFieldMeansOffAndADecimalOneRefusesToSend() throws Exception {
		// As a bundle host may pass the fields: emptied, or with a decimal number typed in.
		var emptied = service(IMAP, SMTP, 60, java.util.Arrays.asList("", " ", null), Optional.empty());
		var copy = emptied.sendEmail(ACCOUNT, USER, null, null, "Emptied", "hello", EmailService.NOT_CANCELLED);
		assertEquals(Duration.ZERO, copy.waited());
		assertTrue(subjectsIn("INBOX").contains("Emptied"));

		var decimal = service(IMAP, SMTP, 60, List.of("120.0", "600", "80"), Optional.empty());
		var refusal = assertThrows(Refusal.class,
				() -> decimal.sendEmail(ACCOUNT, USER, null, null, "Decimal", "hello", EmailService.NOT_CANCELLED))
				.getMessage();
		assertTrue(refusal.contains("EMAIL_ACCOUNTS_TEST_MIN_SEND_DELAY is \"120.0\", which is not a whole number"),
				refusal);
		assertFalse(subjectsIn("INBOX").contains("Decimal"));

		var tools = new EmailTools();
		tools.emailService = decimal;
		tools.allowSending = true;
		var limits = assertThrows(ToolCallException.class, () -> tools.getSendLimits(ACCOUNT)).getMessage();
		assertTrue(limits.contains("is \"120.0\", which is not a whole number"), limits);
	}

	@Test
	void getSendLimitsForAnAccountNotPacedReadsNothing() throws Exception {
		int closedPort;
		try (var socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		var tools = new EmailTools();
		tools.emailService = service(closedPort, closedPort, 60, OFF, Optional.empty());

		tools.allowSending = true;
		assertEquals("Account test is not paced: its messages leave at once, with no pause between them and no daily "
				+ "limit.", tools.getSendLimits(ACCOUNT), "no server is reachable, so none was asked");
		tools.allowSending = false;
		assertTrue(tools.getSendLimits(ACCOUNT).startsWith("Sending is turned off (EMAIL_ALLOW_SENDING)"));
	}

	@Test
	void getSendLimitsNamesTheLastMessageAndTheNextSlot() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, new PacingLimits(120, 600, 80), Optional.empty());
		var tools = new EmailTools();
		tools.emailService = service;
		tools.allowSending = true;
		draws.add(300L);
		var first = tools.sendEmail(ACCOUNT, USER, "", "", "First", "hello", null);
		clock.advance(Duration.ofSeconds(100));

		var limits = tools.getSendLimits(ACCOUNT);

		assertTrue(first.contains("Message-ID: " + messageId("Sent", "First")), first);
		assertTrue(
				limits.startsWith("Account test paces its messages: a pause of 120 to 600 s between two messages, "
						+ "and at most 80 messages a day. Sent today: 1 of 80. The next message may leave at "),
				limits);
		assertTrue(limits.contains(", in 3 min 20 s."), limits);
		assertTrue(limits.contains("subject \"First\", Message-ID " + messageId("Sent", "First")), limits);
		assertTrue(sleeps.isEmpty(), "getSendLimits never waits");
	}

	@Test
	void waitForSendSlotWaitsAtMostTheBoundAndSendsNothing() throws Exception {
		service.createFolder(ACCOUNT, "Sent");
		service = service(IMAP, SMTP, 60, PAUSE, Optional.empty());
		var tools = new EmailTools();
		tools.emailService = service;
		tools.allowSending = true;
		draws.add(300L);
		tools.sendEmail(ACCOUNT, USER, "", "", "First", "hello", null);
		clock.advance(Duration.ofSeconds(100));

		var waited = tools.waitForSendSlot(ACCOUNT);
		clock.advance(Duration.ofSeconds(145));
		var arrived = tools.waitForSendSlot(ACCOUNT);

		assertTrue(waited.startsWith("Waited 45 s. "), waited);
		assertTrue(waited.contains(", in 2 min 35 s."), waited);
		assertTrue(arrived.startsWith("Waited 10 s. "), arrived);
		assertTrue(arrived.contains("The next message may leave now."), arrived);
		assertEquals(List.of(Duration.ofSeconds(45), Duration.ofSeconds(10)), sleeps);
		assertEquals(List.of("A", "B", "C", "First"), subjectsIn("INBOX"), "waiting sends nothing");
		assertEquals(List.of("First"), subjectsIn("Sent"));
	}

	@Test
	void everySendToolTellsNeverToSendAgainAfterAnUncertainFailure() {
		var sendTools = List.of("sendEmail", "replyEmail", "forwardEmail", "forwardEmailWithComment");
		var found = new ArrayList<String>();
		for (var method : EmailTools.class.getDeclaredMethods()) {
			var tool = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
			if (tool != null && sendTools.contains(method.getName())) {
				found.add(method.getName());
				assertTrue(tool.description().contains(EmailTools.PACING_DESCRIPTION), method.getName());
			}
		}
		assertEquals(sendTools.size(), found.size(), "all four send tools were checked: " + found);
		assertTrue(EmailTools.PACING_DESCRIPTION.contains("NEVER send a message again after an error with no result"));
		assertTrue(EmailTools.PACING_DESCRIPTION.contains("Check the Sent folder first"));
		assertTrue(EmailTools.PACING_DESCRIPTION.contains("tell the user"));
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static EmailConfig config(
		int imapPort, int smtpPort, int networkTimeout, List<String> pacing, Optional<String> sentFolder) {
		var imap = new EmailConfig.ImapConfig() {
			public String host() {
				return "localhost";
			}

			public int port() {
				return imapPort;
			}

			public String username() {
				return USER;
			}

			public String password() {
				return PASSWORD;
			}

			public Optional<Boolean> ssl() {
				return Optional.of(false);
			}
		};
		var smtp = new EmailConfig.SmtpConfig() {
			public String host() {
				return "localhost";
			}

			public int port() {
				return smtpPort;
			}

			public String username() {
				return USER;
			}

			public String password() {
				return PASSWORD;
			}

			public boolean starttls() {
				return false;
			}

			public Optional<Boolean> ssl() {
				return Optional.of(false);
			}
		};
		var account = new EmailConfig.AccountConfig() {
			public EmailConfig.ImapConfig imap() {
				return imap;
			}

			public EmailConfig.SmtpConfig smtp() {
				return smtp;
			}

			public Optional<String> from() {
				return Optional.empty();
			}

			public Optional<String> draftsFolder() {
				return Optional.empty();
			}

			public Optional<String> spamFolder() {
				return Optional.empty();
			}

			public Optional<String> trashFolder() {
				return Optional.empty();
			}

			public Optional<String> sentFolder() {
				return sentFolder;
			}

			public boolean saveToSent() {
				return true;
			}

			public Optional<String> minSendDelay() {
				return Optional.ofNullable(pacing.get(0));
			}

			public Optional<String> maxSendDelay() {
				return Optional.ofNullable(pacing.get(1));
			}

			public Optional<String> maxSendsPerDay() {
				return Optional.ofNullable(pacing.get(2));
			}
		};
		return new EmailConfig() {
			public Map<String, AccountConfig> accounts() {
				return Map.of(ACCOUNT, account);
			}

			public int networkTimeout() {
				return networkTimeout;
			}
		};
	}

	/** A time of day in the time zone of the computer running the tests. */
	private static Instant at(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant();
	}

	private static Store connect(int port) throws MessagingException {
		var store = Session.getInstance(new Properties()).getStore("imap");
		store.connect("localhost", port, USER, PASSWORD);
		return store;
	}

	/**
	 * Files a message as another mail client would, with the date the server is to record for it.
	 */
	private static void fileAt(String folderName, String subject, Instant filed) throws Exception {
		try (var store = connect(IMAP)) {
			var message = new MimeMessage(Session.getInstance(new Properties())) {
				@Override
				public Date getReceivedDate() {
					return Date.from(filed);
				}
			};
			message.setFrom(new InternetAddress(USER));
			message.setRecipients(Message.RecipientType.TO, "Jane Doe <jane@example.com>");
			message.setSubject(subject);
			message.setText("hello");
			message.saveChanges();
			store.getFolder(folderName).appendMessages(new Message[] {message});
		}
	}

	/** Flags a message \Deleted without expunging it, as a mail client that purges later does. */
	private static void flagDeleted(String folderName, String subject) throws Exception {
		try (var store = connect(IMAP)) {
			var folder = store.getFolder(folderName);
			folder.open(Folder.READ_WRITE);
			try {
				for (var m : folder.getMessages()) {
					if (subject.equals(m.getSubject())) {
						m.setFlag(Flags.Flag.DELETED, true);
					}
				}
			} finally {
				folder.close(false);
			}
		}
	}

	/** The message with this subject, as another client sees it on the server. */
	private static <T> T inFolder(String folderName, String subject, MessageReader<T> reader) throws Exception {
		try (var store = connect(IMAP)) {
			var folder = store.getFolder(folderName);
			folder.open(Folder.READ_ONLY);
			try {
				for (var m : folder.getMessages()) {
					if (subject.equals(m.getSubject())) {
						return reader.read((MimeMessage) m);
					}
				}
				throw new AssertionError("No message with subject " + subject + " in " + folderName);
			} finally {
				folder.close(false);
			}
		}
	}

	@FunctionalInterface
	private interface MessageReader<T> {
		T read(MimeMessage message) throws Exception;
	}

	private static String messageId(String folderName, String subject) throws Exception {
		return inFolder(folderName, subject, MimeMessage::getMessageID);
	}

	private static String source(String folderName, String subject) throws Exception {
		return inFolder(folderName, subject, m -> {
			var out = new ByteArrayOutputStream();
			m.writeTo(out);
			return out.toString(StandardCharsets.UTF_8);
		});
	}

	private static List<String> subjectsIn(String folderName) throws Exception {
		try (var store = connect(IMAP)) {
			var folder = store.getFolder(folderName);
			folder.open(Folder.READ_ONLY);
			try {
				var subjects = new ArrayList<String>();
				for (var m : folder.getMessages()) {
					subjects.add(m.getSubject());
				}
				subjects.sort(null);
				return subjects;
			} finally {
				folder.close(false);
			}
		}
	}

	private long uidOf(String folderName, String subject) throws Exception {
		for (var h : service.listEmails(ACCOUNT, folderName, 0, 100, "date")) {
			if (subject.equals(h.subject())) {
				return h.uid();
			}
		}
		throw new AssertionError("No message with subject " + subject + " in " + folderName);
	}

	private static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception e) {
			// Already gone.
		}
	}

	/**
	 * Stands between the service and the IMAP server, keeps what the client sends, and drops every
	 * connection on demand: the client then sees what it would if the server closed an idle
	 * connection.
	 */
	private static final class CuttableProxy implements AutoCloseable {
		private final ServerSocket server = new ServerSocket(0);
		private final List<Socket> sockets = new CopyOnWriteArrayList<>();
		private final ExecutorService threads = Executors.newCachedThreadPool();
		private final ByteArrayOutputStream fromClient = new ByteArrayOutputStream();
		private final int target;

		CuttableProxy(int target) throws IOException {
			this.target = target;
			threads.submit(this::accept);
		}

		int port() {
			return server.getLocalPort();
		}

		/** Everything the clients have sent so far. */
		String sent() {
			synchronized (fromClient) {
				return fromClient.toString(StandardCharsets.ISO_8859_1);
			}
		}

		private void accept() {
			while (!server.isClosed()) {
				try {
					var client = server.accept();
					var upstream = new Socket("localhost", target);
					sockets.add(client);
					sockets.add(upstream);
					threads.submit(() -> pump(client, upstream, fromClient));
					threads.submit(() -> pump(upstream, client, null));
				} catch (IOException e) {
					return;
				}
			}
		}

		private static void pump(Socket from, Socket to, ByteArrayOutputStream copy) {
			try {
				var in = from.getInputStream();
				var out = to.getOutputStream();
				var buffer = new byte[8192];
				int n;
				while ((n = in.read(buffer)) >= 0) {
					if (copy != null) {
						synchronized (copy) {
							copy.write(buffer, 0, n);
						}
					}
					out.write(buffer, 0, n);
				}
			} catch (IOException e) {
				// The connection was dropped.
			}
			closeQuietly(from);
			closeQuietly(to);
		}

		/** Drops every open connection. */
		void cut() {
			for (var socket : sockets) {
				closeQuietly(socket);
			}
			sockets.clear();
		}

		@Override
		public void close() {
			closeQuietly(server);
			cut();
			threads.shutdownNow();
		}
	}

	/** An SMTP server that greets, answers EHLO, then never answers again. */
	private static final class SilentSmtpServer implements AutoCloseable {
		private final ServerSocket server = new ServerSocket(0);

		SilentSmtpServer() throws IOException {
			var thread = new Thread(() -> {
				try (var socket = server.accept()) {
					var out = socket.getOutputStream();
					var in = new BufferedReader(
							new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
					out.write("220 silent ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
					out.flush();
					in.readLine();
					out.write("250 silent\r\n".getBytes(StandardCharsets.US_ASCII));
					out.flush();
					while (in.readLine() != null) {
						// Never answers.
					}
				} catch (IOException e) {
					// The client gave up.
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		int port() {
			return server.getLocalPort();
		}

		@Override
		public void close() {
			closeQuietly(server);
		}
	}
}
