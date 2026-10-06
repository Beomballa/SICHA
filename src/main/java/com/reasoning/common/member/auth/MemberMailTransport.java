package com.reasoning.common.member.auth;

import com.reasoning.common.auth.service.AuthException;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 자격 없는 루프백 SMTP만 허용하는 LOCAL 합성 수집 경계다. 운영 메일 활성화가 아니다. */
public final class MemberMailTransport {
    public record Boundary(
            String host, int port, String protocol, String from, int timeoutMillis) {}

    private final ObjectProvider<JavaMailSender> senders;
    private final MemberAuthConfiguration.Properties configuration;

    public MemberMailTransport(
            ObjectProvider<JavaMailSender> senders,
            MemberAuthConfiguration.Properties configuration) {
        this.senders = java.util.Objects.requireNonNull(senders);
        this.configuration = java.util.Objects.requireNonNull(configuration);
    }

    public Boundary boundary() {
        try {
            JavaMailSender sender = senders.getIfAvailable();
            if (!(sender instanceof JavaMailSenderImpl actual)) throw unavailable();
            String host = actual.getHost();
            if (!java.util.Set.of("localhost", "127.0.0.1", "::1").contains(host)
                    || actual.getPort() < 1
                    || actual.getPort() > 65535
                    || !"smtp".equals(actual.getProtocol())
                    || actual.getUsername() != null && !actual.getUsername().isEmpty()
                    || actual.getPassword() != null && !actual.getPassword().isEmpty())
                throw unavailable();
            Properties settings = actual.getJavaMailProperties();
            if (Boolean.parseBoolean(settings.getProperty("mail.smtp.auth", "false"))
                    || Boolean.parseBoolean(settings.getProperty("mail.smtp.ssl.enable", "false"))
                    || Boolean.parseBoolean(
                            settings.getProperty("mail.smtp.starttls.enable", "false"))
                    || settings.containsKey("mail.smtp.socketFactory.class")
                    || settings.containsKey("mail.smtp.proxy.host")
                    || settings.containsKey("mail.smtp.socks.host")
                    || settings.containsKey("mail.smtp.host")
                    || settings.containsKey("mail.smtp.port")) throw unavailable();
            synchronized (actual) {
                settings.setProperty("mail.smtp.connectiontimeout", "5000");
                settings.setProperty("mail.smtp.timeout", "5000");
                settings.setProperty("mail.smtp.writetimeout", "5000");
                settings.setProperty("mail.debug", "false");
                actual.getSession().setDebug(false);
            }
            String from = configuration.getMailFrom();
            InternetAddress address = new InternetAddress(from, true);
            address.validate();
            if (from == null
                    || from.contains("\r")
                    || from.contains("\n")
                    || !from.equals(address.getAddress())
                    || address.getPersonal() != null) throw unavailable();
            return new Boundary(host, actual.getPort(), "smtp", from, 5000);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    /** 발송 실패는 계정 존재를 노출하지 않고 시작 봉투의 의미도 바꾸지 않는다. */
    public boolean sendSignup(String email, String code, Boundary expected) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            if (!boundary().equals(expected)) return false;
            JavaMailSender sender = senders.getIfAvailable();
            if (sender == null) return false;
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(expected.from());
            helper.setTo(email);
            helper.setSubject("회원 가입 이메일 확인");
            helper.setText("회원 가입 확인 코드: " + code + "\n코드는 가입 시작부터 10분 동안만 유효합니다.", false);
            sender.send(message);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("COLLECTION_NOT_READY");
    }
}
