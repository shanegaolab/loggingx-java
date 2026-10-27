package com.loggingx.biz.notify;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A minimal SMTP client for alert mails: implicit TLS ({@code ssl}, port 465), STARTTLS ({@code starttls}, port 587)
 * or plain ({@code none}, port 25), {@code AUTH LOGIN}, and a UTF-8 HTML body. Server certificates are verified by the
 * JVM's trust store.
 */
public class SmtpClient {

    /**
     * @param security {@code ssl}, {@code starttls} or {@code none}
     * @param username empty for servers that need no login
     */
    public record Server(String host, int port, String security, String username, String password, String from,
                         String fromName) {
    }

    /**
     * A failed delivery, with the server's reply.
     */
    public static class SmtpException extends RuntimeException {
        SmtpException(String message) {
            super(message);
        }
    }

    private static final int TIMEOUT_MILLIS = 15_000;

    private final Server server;

    public SmtpClient(Server server) {
        this.server = server;
    }

    /**
     * Sends one mail to all recipients.
     *
     * @throws SmtpException when the server cannot be reached or refuses the mail
     */
    public void send(List<String> to, String subject, String html) {
        boolean ssl = "ssl".equals(server.security());
        try (Socket plain = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket()) {
            plain.connect(new InetSocketAddress(server.host(), server.port()), TIMEOUT_MILLIS);
            plain.setSoTimeout(TIMEOUT_MILLIS);
            Socket socket = plain;
            Session session = new Session(socket);
            session.expect(220);
            session.command("EHLO loggingx", 250);
            if ("starttls".equals(server.security())) {
                session.command("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, server.host(), server.port(), true);
                tls.setSoTimeout(TIMEOUT_MILLIS);
                tls.startHandshake();
                socket = tls;
                session = new Session(socket);
                session.command("EHLO loggingx", 250);
            }
            if (server.username() != null && !server.username().isEmpty()) {
                session.command("AUTH LOGIN", 334);
                session.command(base64(server.username()), 334);
                session.command(base64(server.password() == null ? "" : server.password()), 235);
            }
            session.command("MAIL FROM:<" + server.from() + ">", 250);
            for (String recipient : to) {
                session.command("RCPT TO:<" + recipient + ">", 250, 251);
            }
            session.command("DATA", 354);
            session.write(message(to, subject, html));
            session.command(".", 250);
            session.command("QUIT", 221);
        } catch (IOException e) {
            throw new SmtpException("Cannot reach " + server.host() + ":" + server.port() + ": " + e.getMessage());
        }
    }

    String message(List<String> to, String subject, String html) {
        String fromName = server.fromName() == null || server.fromName().isBlank() ? "LoggingX" : server.fromName();
        StringBuilder message = new StringBuilder()
                .append("From: ").append(encodeWord(fromName)).append(" <").append(server.from()).append(">\r\n")
                .append("To: ").append(String.join(", ", to)).append("\r\n")
                .append("Subject: ").append(encodeWord(subject)).append("\r\n")
                .append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)
                        .format(ZonedDateTime.now())).append("\r\n")
                .append("Message-ID: <").append(UUID.randomUUID()).append("@loggingx>\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n");
        String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(html.getBytes(StandardCharsets.UTF_8));
        // Base64 has no line starting with ".", so no dot-stuffing is needed.
        return message.append(body).append("\r\n").toString();
    }

    /**
     * @return the text as an RFC 2047 encoded word when it is not plain ASCII
     */
    static String encodeWord(String text) {
        if (text.chars().allMatch(c -> c >= 32 && c < 127)) {
            return text;
        }
        return "=?UTF-8?B?" + base64(text) + "?=";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Commands and replies over one socket; multi-line replies ({@code 250-...}) are read to their last line.
     */
    private static final class Session {
        private final BufferedReader in;
        private final OutputStream out;

        Session(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = socket.getOutputStream();
        }

        void command(String line, int... expected) throws IOException {
            write(line + "\r\n");
            expect(expected);
        }

        void write(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void expect(int... expected) throws IOException {
            String line;
            StringBuilder reply = new StringBuilder();
            do {
                line = in.readLine();
                if (line == null) {
                    throw new SmtpException("The mail server closed the connection" + (reply.isEmpty() ? ""
                            : ": " + reply));
                }
                reply.append(reply.isEmpty() ? "" : " ").append(line);
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code;
            try {
                code = Integer.parseInt(line.substring(0, 3));
            } catch (RuntimeException e) {
                throw new SmtpException("Unexpected reply from the mail server: " + reply);
            }
            for (int ok : expected) {
                if (code == ok) {
                    return;
                }
            }
            throw new SmtpException("The mail server refused: " + reply);
        }
    }
}
