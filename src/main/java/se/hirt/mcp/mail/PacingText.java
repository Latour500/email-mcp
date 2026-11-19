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

import se.hirt.mcp.mail.SendPacing.SentRecord;
import se.hirt.mcp.mail.SendPacing.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The texts of {@link SendPacing}: what getSendLimits and waitForSendSlot say, and the times,
 * durations and last message that the refusals name. Times are told on the pacing clock, with the
 * date only when it is not today.
 */
final class PacingText {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Clock clock;

	PacingText(Clock clock) {
		this.clock = clock;
	}

	/** What getSendLimits says of an account that is not paced. */
	static String notPaced(String account) {
		return "Account " + account + " is not paced: its messages leave at once, with no pause between them and no "
				+ "daily limit.";
	}

	/** The status of a paced account, as getSendLimits and waitForSendSlot tell it. */
	String describe(String account, Status status) {
		var limits = status.limits();
		var text = new StringBuilder("Account ").append(account).append(" paces its messages:");
		if (limits.pauseOn()) {
			text.append(" a pause of ").append(limits.minDelay()).append(" to ").append(limits.maxDelay())
					.append(" s between two messages");
		}
		if (limits.limitOn()) {
			text.append(limits.pauseOn() ? ", and" : "").append(" at most ").append(limits.maxPerDay())
					.append(" messages a day");
		}
		text.append(". Sent today: ").append(status.sentToday());
		if (limits.limitOn()) {
			text.append(" of ").append(limits.maxPerDay());
		}
		text.append(".");
		var next = status.nextSlot();
		if (next == null) {
			text.append(" The next message may leave now.");
		} else if (status.limitReached()) {
			text.append(" The daily limit is reached: the next message may leave from ").append(time(next)).append(".");
		} else {
			text.append(" The next message may leave at ").append(time(next)).append(", in ")
					.append(duration(Duration.between(clock.instant(), next))).append(".");
		}
		return text.append(lastSent(status.last())).toString();
	}

	/**
	 * Names the last message sent, so that a client unsure whether it left does not send it twice.
	 */
	String lastSent(SentRecord last) {
		if (last == null) {
			return "";
		}
		return " Last message sent at " + time(last.at()) + " to " + orUnknown(last.to()) + ", subject \""
				+ (last.subject() != null ? last.subject() : "") + "\", Message-ID " + orUnknown(last.messageId())
				+ ": if that is the message about to be sent, it has already left, do not send it again.";
	}

	private static String orUnknown(String value) {
		return value != null && !value.isBlank() ? value : "unknown";
	}

	/** The time of day, with the date when it is not today. */
	String time(Instant instant) {
		var at = instant.atZone(clock.getZone());
		return at.toLocalDate().equals(LocalDate.now(clock)) ? at.format(TIME) : at.format(DATE_TIME);
	}

	/** A duration rounded up to the second, such as {@code 7 min 12 s}. */
	static String duration(Duration duration) {
		long seconds = Math.max(0, (duration.toMillis() + 999) / 1000);
		long minutes = seconds / 60;
		long rest = seconds % 60;
		if (minutes == 0) {
			return rest + " s";
		}
		return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
	}
}
