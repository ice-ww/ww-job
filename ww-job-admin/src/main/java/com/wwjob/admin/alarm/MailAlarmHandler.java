package com.wwjob.admin.alarm;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;


/**
 * @author 王威
 * @version 1.0
 */

@Component
public class MailAlarmHandler implements AlarmHandler {
    private final JavaMailSender mailSender;
    public MailAlarmHandler(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(String alarmConfig, String title, String content) throws Exception {
        // 依次发给每个收件人
        String[] emails = alarmConfig.split(",");
        for (String email : emails) {
            String e = email.trim();
            if (e.isEmpty()) {
                continue;
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(e);
            msg.setSubject(title);
            msg.setText(content);
            mailSender.send(msg);
        }
    }
}
