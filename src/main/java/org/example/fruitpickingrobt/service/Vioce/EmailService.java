package org.example.fruitpickingrobt.service.Vioce;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.mail.internet.MimeMessage;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class EmailService {
    @Resource
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String username;

    public void sendCode(String to, String code) {
        try {
            try {
                sendHtmlMail(to, generateHtmlContent(code));
            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
            log.info("验证码发送成功，收件人{}",to);
        } catch (MailException e) {
            log.info("发送邮箱验证码失败 ({}): {}", to, code);
            throw new RuntimeException("邮件发送失败，请检查邮箱配置", e);
        }
    }

    protected void sendHtmlMail(String to, String content) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        try {
            helper.setFrom(new InternetAddress(username, "果实采摘机器人", StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        helper.setTo(to);
        helper.setSubject("FruitPickingRobt验证码");
        helper.setText(content, true);
        mailSender.send(message);
    }

    private String generateHtmlContent(String code) {
        return String.format("""
                <div style="font-family: 'Microsoft YaHei', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h3 style="color: #2c3e50; margin-bottom: 20px;">尊敬的用户：</h3>
                    <p style="font-size: 16px; color: #34495e; line-height: 1.6;">
                        您正在进行邮箱验证操作，您的验证码为：
                    </p>
                    <div style="background: #f8f9fa; border-radius: 8px; padding: 15px; margin: 20px 0; text-align: center;">
                        <span style="font-size: 24px; font-weight: bold; color: #3498db;">%s</span>
                    </div>
                    <p style="font-size: 14px; color: #7f8c8d;">
                        验证码有效期为5分钟，请尽快完成验证。<br>
                        请勿向他人泄露验证码，如非本人操作，请忽略此邮件。
                    </p>
                </div>
                """, code);
    }
}
