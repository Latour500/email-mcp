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

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import se.hirt.mcp.mail.SendPacing.SentRecord;
import se.hirt.mcp.mail.SendPacing.SentSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * The IMAP connection of one paced send. It is opened only when first used, with a short network
 * timeout so that a silent server cannot hold the send call for long. It reads the Sent folder for
 * {@link SendPacing}, can then serve to save the copy of the message, and is closed at the end of
 * the call. The shared connection of the other tools keeps its own, longer timeout.
 * <p>
 * A server may close a connection left idle, for instance during the pause before the send. Such a
 * connection is opened again, once per call: a second failure is reported as it is.
 * <p>
 * Used by one call at a time, from one thread.
 */
final class PacedImapConnection implements SendPacing.SentLog, AutoCloseable {

	/** Something fetched over the network. */
	@FunctionalInterface
	interface MailSupplier<T> {
		T get() throws MessagingException;
	}

	private final MailSupplier<Store> opener;
	private final MailSupplier<String> sentFolder;
	private Store store;
	private boolean reopened;

	/**
	 * @param opener
	 *            opens the connection, on first use
	 * @param sentFolder
	 *            the full name of the account's Sent folder, or {@code null} if there is none
	 */
	PacedImapConnection(MailSupplier<Store> opener, MailSupplier<String> sentFolder) {
		this.opener = opener;
		this.sentFolder = sentFolder;
	}

	/**
	 * The connection, opened on first use. Once it has been used, the server is asked whether it is
	 * still there (an IMAP NOOP), and it is opened again if the server closed it.
	 */
	Store store() throws MessagingException {
		if (store == null) {
			store = opener.get();
		} else {
			reopenIfClosed();
		}
		return store;
	}

	/**
	 * Opens the connection again if the server closed it, once per call; returns whether it did.
	 */
	private boolean reopenIfClosed() throws MessagingException {
		if (reopened || store == null || store.isConnected()) {
			return false;
		}
		reopened = true;
		close();
		store = opener.get();
		return true;
	}

	/**
	 * Counts the messages filed in the Sent folder since {@code countSince}, and finds the latest
	 * one filed since {@code latestSince}, by the time the server filed them (INTERNALDATE). IMAP
	 * searches by whole days, in the server's time zone, so the search starts a day early and the
	 * exact times are compared here.
	 */
	@Override
	public SentSummary read(Instant countSince, Instant latestSince) throws MessagingException {
		var name = sentFolder.get();
		if (name == null) {
			throw new MessagingException("no Sent folder found. Set the Sent folder "
					+ "(EMAIL_ACCOUNTS_<NAME>_SENT_FOLDER), or call listFolderTree and setSentFolder to set it for "
					+ "this session");
		}
		try {
			return read(store(), name, countSince, latestSince);
		} catch (MessagingException e) {
			// Only a connection the server dropped is worth a second try; any other failure stands.
			if (!reopenIfClosed()) {
				throw e;
			}
			return read(store, name, countSince, latestSince);
		}
	}

	private static SentSummary read(Store store, String name, Instant countSince, Instant latestSince)
			throws MessagingException {
		var folder = store.getFolder(name);
		folder.open(Folder.READ_ONLY);
		try {
			var earliest = countSince.isBefore(latestSince) ? countSince : latestSince;
			var since = Date.from(earliest.minus(Duration.ofDays(1)));
			var messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
			var fp = new FetchProfile();
			fp.add(FetchProfile.Item.ENVELOPE);
			folder.fetch(messages, fp);

			int count = 0;
			Message latest = null;
			Instant latestAt = null;
			for (var m : messages) {
				var filed = filedAt(m);
				if (filed == null) {
					continue;
				}
				if (!filed.isBefore(countSince)) {
					count++;
				}
				if (!filed.isBefore(latestSince) && (latestAt == null || filed.isAfter(latestAt))) {
					latest = m;
					latestAt = filed;
				}
			}
			if (latest == null) {
				return new SentSummary(count, Optional.empty());
			}
			var to = latest.getRecipients(Message.RecipientType.TO);
			return new SentSummary(count,
					Optional.of(new SentRecord(latestAt, to != null ? InternetAddress.toUnicodeString(to) : null,
							latest.getSubject(), latest instanceof MimeMessage mm ? mm.getMessageID() : null)));
		} finally {
			folder.close(false);
		}
	}

	/** When the server filed the message, or when it was sent if the server does not say. */
	private static Instant filedAt(Message message) throws MessagingException {
		var date = message.getReceivedDate();
		if (date == null) {
			date = message.getSentDate();
		}
		return date != null ? date.toInstant() : null;
	}

	@Override
	public void close() {
		if (store != null) {
			try {
				store.close();
			} catch (MessagingException e) {
				// Nothing is left to do with this connection.
			}
			store = null;
		}
	}
}
