/*
 * Copyright (C) 2026 Marcus Hirt
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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "email")
public interface EmailConfig {

	Map<String, AccountConfig> accounts();

	/**
	 * Timeout in seconds for every IMAP and SMTP connection of every account: connecting, waiting
	 * for the server to answer, and writing to it. Without one, a stalled server hangs the tool
	 * call, and with the STDIO transport the whole conversation, for good. The read timeout is per
	 * socket read, not per operation, so a large download over a slow link is not cut off as long
	 * as bytes keep arriving; only a server that goes silent trips it. {@code 0} disables the
	 * timeouts. {@code EMAIL_NETWORK_TIMEOUT} in the environment.
	 */
	@WithDefault("60")
	int networkTimeout();

	interface AccountConfig {
		ImapConfig imap();

		SmtpConfig smtp();

		/**
		 * The From address of everything this account sends or drafts: a bare address, or
		 * {@code Display Name <address>}. Defaults to the SMTP username, which is the address
		 * itself for most providers. Needed when the SMTP login is not an email address, or to send
		 * from an alias or with a display name. {@code EMAIL_ACCOUNTS_<NAME>_FROM} in the
		 * environment.
		 */
		Optional<String> from();

		/**
		 * Full IMAP name of the Drafts folder, e.g. {@code INBOX.INBOX.Drafts}. When set, it is
		 * used as is and auto-detection is skipped; a name that does not exist on the server is
		 * reported as an error so that the folder can be overridden for the session with the
		 * setDraftsFolder tool.
		 */
		Optional<String> draftsFolder();

		/**
		 * Full IMAP name of the spam/junk folder. Same semantics as {@link #draftsFolder()},
		 * overridable with the setSpamFolder tool.
		 */
		Optional<String> spamFolder();

		/**
		 * Full IMAP name of the trash folder that deleteEmail moves messages to. Same semantics as
		 * {@link #draftsFolder()}, overridable with the setTrashFolder tool.
		 */
		Optional<String> trashFolder();

		/**
		 * Full IMAP name of the Sent folder that a copy of every sent message is saved to. Same
		 * semantics as {@link #draftsFolder()}, overridable with the setSentFolder tool.
		 */
		Optional<String> sentFolder();

		/**
		 * Save a copy of every message sent by sendEmail, replyEmail and forwardEmail to the Sent
		 * folder, as a mail client does. Turn it off for providers that already file sent messages
		 * themselves, such as Gmail, to avoid duplicates.
		 * {@code EMAIL_ACCOUNTS_<NAME>_SAVE_TO_SENT} in the environment.
		 */
		@WithDefault("true")
		boolean saveToSent();

		/*
		 * The three pacing settings below are read as text and checked only when a message is about
		 * to be sent (see PacingLimits): unset, blank or an unexpanded ${...} placeholder means 0,
		 * a whole number is taken as is, and anything else makes the send tools refuse to send.
		 * Read as numbers, an empty or decimal value would stop the whole server from starting.
		 */

		/**
		 * Shortest pause, in seconds, between two messages sent by this account, for example
		 * {@code 120}. After each send, the pause before the next one is drawn at random between
		 * this and {@link #maxSendDelay()}. The previous send is also looked up in the Sent folder,
		 * so the pause holds after a restart. When more than 45 seconds of it are left, the send
		 * tools send nothing and say when the next message may leave. {@code 0} for both settings,
		 * the default, turns the pause off: messages leave at once, with no lock and no lookup in
		 * the Sent folder. {@code EMAIL_ACCOUNTS_<NAME>_MIN_SEND_DELAY} in the environment.
		 */
		Optional<String> minSendDelay();

		/**
		 * Longest pause, in seconds, between two messages sent by this account, for example
		 * {@code 600}. Once it is set, {@link #minSendDelay()} must be at least 1 second and at
		 * most this value, and no setting may be negative: otherwise the send tools refuse to send
		 * until the settings are fixed. {@code EMAIL_ACCOUNTS_<NAME>_MAX_SEND_DELAY} in the
		 * environment.
		 */
		Optional<String> maxSendDelay();

		/**
		 * Most messages this account may send in a day, from midnight to midnight in the computer's
		 * time zone, for example {@code 80}. The count is the larger of the messages filed in the
		 * Sent folder since midnight, which includes those sent from another mail client, and of
		 * those sent by this server since midnight. If the Sent folder cannot be read, nothing is
		 * sent. {@code 0}, the default, means no limit and no lookup in the Sent folder.
		 * {@code EMAIL_ACCOUNTS_<NAME>_MAX_SENDS_PER_DAY} in the environment.
		 */
		Optional<String> maxSendsPerDay();
	}

	interface ImapConfig {
		String host();

		@WithDefault("993")
		int port();

		String username();

		String password();

		/**
		 * Connect with TLS from the first byte (port 993). Unset means: on unless the port is 143,
		 * the plain IMAP port. See {@link EmailService#imapSsl}.
		 */
		Optional<Boolean> ssl();
	}

	interface SmtpConfig {
		String host();

		@WithDefault("587")
		int port();

		String username();

		String password();

		/**
		 * Upgrade the connection with STARTTLS. When enabled the upgrade is required: a server that
		 * does not offer it is refused rather than talked to in the clear. This is the usual setup
		 * on port 587. Ignored when {@link #ssl()} is on, since that connection is encrypted from
		 * the start.
		 */
		@WithDefault("true")
		boolean starttls();

		/**
		 * Connect with TLS from the first byte (implicit TLS, "SMTPS"), the usual setup on port
		 * 465. Unset means: on if the port is 465, otherwise off, which keeps the port 587 +
		 * STARTTLS behaviour for existing configurations. See {@link EmailService#smtpSsl}.
		 */
		Optional<Boolean> ssl();
	}
}
