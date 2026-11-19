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

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic build only, never merged: measures how long an MCP client lets a tool call run.
 * diagWait waits the given number of seconds, sends a progress notification every 10 seconds when
 * the client asked for progress, and stops as soon as the client cancels the call. Every step is
 * appended to email-mcp-diag.log in the user's home directory, so the outcome is known even when
 * the client has given up on the call.
 */
public class DiagTools {

	static final int MAX_SECONDS = 900;
	private static final int PROGRESS_EVERY_SECONDS = 10;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static final AtomicInteger CALLS = new AtomicInteger();
	private static final Logger LOG = Logger.getLogger(DiagTools.class);

	static Path logFile() {
		return Path.of(System.getProperty("user.home"), "email-mcp-diag.log");
	}

	@Tool(description = "Diagnostic only: waits the given number of seconds (1 to 900), then returns. Sends a "
			+ "progress notification every 10 seconds if the client asked for progress, and stops early if the "
			+ "client cancels the call. Every step is written to email-mcp-diag.log in the user's home directory. "
			+ "Reads and sends no email.")
	String diagWait(@ToolArg(description = "Number of seconds to wait, 1 to 900")
	int seconds, Progress progress, Cancellation cancellation) {
		if (seconds < 1 || seconds > MAX_SECONDS) {
			throw new ToolCallException("seconds must be between 1 and " + MAX_SECONDS + ", got " + seconds);
		}
		int call = CALLS.incrementAndGet();
		var token = progress.token();
		log(call, "start: " + seconds + " s requested, progress token: "
				+ token.map(t -> "yes (" + t.value() + ")").orElse("no") + ", pid " + ProcessHandle.current().pid());
		long start = System.nanoTime();
		int sent = 0;
		for (int elapsed = 1; elapsed <= seconds; elapsed++) {
			try {
				long remaining = start + TimeUnit.SECONDS.toNanos(elapsed) - System.nanoTime();
				if (remaining > 0) {
					TimeUnit.NANOSECONDS.sleep(remaining);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log(call, "interrupted at " + elapsed + " s");
				throw new ToolCallException("diagWait interrupted at " + elapsed + " s");
			}
			var cancel = cancellation.check();
			if (cancel.isRequested()) {
				log(call, "cancellation received at " + elapsed + " s, reason: " + cancel.reason().orElse("none"));
				return "Cancelled by the client at " + elapsed + " s.";
			}
			if (token.isPresent() && elapsed % PROGRESS_EVERY_SECONDS == 0 && elapsed < seconds) {
				progress.notificationBuilder().setProgress(elapsed).setTotal(seconds)
						.setMessage(elapsed + " of " + seconds + " s").build().sendAndForget();
				sent++;
				log(call, "progress sent at " + elapsed + " s");
			}
		}
		log(call, "end of wait after " + seconds + " s, " + sent + " progress notifications sent, returning result");
		return "Waited " + seconds + " s. Progress token received: " + (token.isPresent() ? "yes" : "no") + ". " + sent
				+ " progress notifications sent. Log: " + logFile();
	}

	private static void log(int call, String event) {
		var line = LocalDateTime.now().format(TIME) + " call " + call + " " + event + System.lineSeparator();
		try {
			Files.writeString(logFile(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warnf("diagWait could not write %s: %s (%s)", logFile(), e.getMessage(), line.strip());
		}
	}
}
