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

import se.hirt.mcp.mail.SendPacing.Refusal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The pacing settings of one account, once checked: the shortest and longest pause between two
 * messages, in seconds, and the most messages a day. {@code 0} turns a setting off.
 * <p>
 * The settings come in as text and are checked only when a message is about to leave, so that a
 * wrong value is reported by the send tools instead of stopping the whole server from starting.
 */
record PacingLimits(int minDelay, int maxDelay, int maxPerDay) {

	private static final List<String> NAMES = List.of("MIN_SEND_DELAY", "MAX_SEND_DELAY", "MAX_SENDS_PER_DAY");

	static PacingLimits of(String account, EmailConfig.AccountConfig config) throws Refusal {
		return of(account, config.minSendDelay(), config.maxSendDelay(), config.maxSendsPerDay());
	}

	/**
	 * Reads the settings as the configuration gives them, as text: unset, blank or an unexpanded
	 * {@code ${...}} placeholder is 0, a whole number is taken as is, and anything else is refused,
	 * naming the value.
	 */
	static PacingLimits of(
		String account, Optional<String> minDelay, Optional<String> maxDelay, Optional<String> maxPerDay)
			throws Refusal {
		var values = new int[3];
		var settings = List.of(minDelay, maxDelay, maxPerDay);
		var notWhole = new ArrayList<String>();
		for (int i = 0; i < 3; i++) {
			var text = EmailService.setting(settings.get(i));
			try {
				values[i] = text.isEmpty() ? 0 : Integer.parseInt(text.get());
			} catch (NumberFormatException e) {
				notWhole.add(variable(account, NAMES.get(i)) + " is \"" + text.get() + "\"");
			}
		}
		if (!notWhole.isEmpty()) {
			throw invalid(account, String.join(" and ", notWhole)
					+ (notWhole.size() == 1 ? ", which is not a whole number" : ", which are not whole numbers"));
		}
		return of(account, values[0], values[1], values[2]);
	}

	static PacingLimits of(String account, int minDelay, int maxDelay, int maxPerDay) throws Refusal {
		String problem = null;
		if (minDelay < 0 || maxDelay < 0 || maxPerDay < 0) {
			problem = "no setting may be negative";
		} else if (maxDelay > 0 && minDelay < 1) {
			problem = "the minimum pause must be at least 1 second when a maximum is set";
		} else if (minDelay > maxDelay) {
			problem = "the maximum pause is below the minimum";
		}
		if (problem != null) {
			throw invalid(account,
					problem + " (" + variable(account, NAMES.get(0)) + "=" + minDelay + ", "
							+ variable(account, NAMES.get(1)) + "=" + maxDelay + ", " + variable(account, NAMES.get(2))
							+ "=" + maxPerDay + ")");
		}
		return new PacingLimits(minDelay, maxDelay, maxPerDay);
	}

	/** The environment variable of a pacing setting of the account. */
	private static String variable(String account, String name) {
		return "EMAIL_ACCOUNTS_" + account.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_" + name;
	}

	private static Refusal invalid(String account, String problem) {
		return new Refusal("The send pacing settings of account " + account + " are invalid: " + problem
				+ ". Nothing can be sent from this account until they are fixed in the extension settings or the "
				+ "environment and the server is restarted.");
	}

	boolean pauseOn() {
		return maxDelay > 0;
	}

	boolean limitOn() {
		return maxPerDay > 0;
	}

	boolean on() {
		return pauseOn() || limitOn();
	}
}
