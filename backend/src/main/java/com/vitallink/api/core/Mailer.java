package com.vitallink.api.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text email over SMTP (Mailpit locally — http://localhost:8025 — or any provider via SMTP_*). A failed
 * send is logged and reported as false, never thrown: callers like Forgot password must answer the same way
 * whether or not an email went out.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final JavaMailSender sender;
    private final String from;

    public Mailer(JavaMailSender sender, @Value("${vitallink.mail-from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public boolean send(String to, String subject, String body) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        try {
            sender.send(m);
            return true;
        } catch (MailException e) {
            log.error("couldn't send \"{}\" email: {}", subject, e.getMessage());
            return false;
        }
    }
}
