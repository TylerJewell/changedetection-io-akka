package io.akka.changedetection.notification;

import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Delivery by electronic mail.
 *
 * <p>The whole configuration lives in the address: the server, the credentials, who it is from
 * and who it goes to. That is the shape the original's notification addresses take, and it is
 * what lets a watch, a tag and the global settings each carry their own destination without any
 * of them needing a separate settings page.
 */
public final class MailSender implements Sender {

  @Override
  public List<String> schemes() {
    return List.of("mailto", "mailtos");
  }

  @Override
  public void send(Message message) {
    URI address = URI.create(message.url());
    boolean secure = address.getScheme().equalsIgnoreCase("mailtos");

    String userInfo = address.getRawUserInfo();
    String username = null;
    String password = null;
    if (userInfo != null) {
      int colon = userInfo.indexOf(':');
      username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
      password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
    }

    String host = address.getHost();
    int port = address.getPort() > 0 ? address.getPort() : (secure ? 587 : 25);

    Map<String, String> options = new LinkedHashMap<>();
    if (address.getRawQuery() != null) {
      for (String pair : address.getRawQuery().split("&")) {
        int equals = pair.indexOf('=');
        if (equals > 0) {
          options.put(
              decode(pair.substring(0, equals)).toLowerCase(Locale.ROOT),
              decode(pair.substring(equals + 1)));
        }
      }
    }

    List<String> recipients = new ArrayList<>();
    String path = address.getRawPath();
    if (path != null && path.length() > 1) {
      for (String part : path.substring(1).split(",")) {
        if (!part.isBlank()) {
          recipients.add(decode(part.strip()));
        }
      }
    }
    if (options.containsKey("to")) {
      for (String part : options.get("to").split(",")) {
        if (!part.isBlank()) {
          recipients.add(part.strip());
        }
      }
    }
    String from =
        options.containsKey("from")
            ? options.get("from")
            : (username != null && username.contains("@") ? username : "changedetection@" + host);
    if (recipients.isEmpty() && username != null && username.contains("@")) {
      recipients.add(username);
    }
    if (recipients.isEmpty()) {
      throw new NotificationFailed("The address names nobody to send to");
    }

    Properties properties = new Properties();
    properties.put("mail.smtp.host", host);
    properties.put("mail.smtp.port", String.valueOf(port));
    properties.put("mail.smtp.auth", String.valueOf(username != null && password != null));
    if (secure) {
      properties.put("mail.smtp.starttls.enable", "true");
    }
    if (options.containsKey("mode") && options.get("mode").equalsIgnoreCase("ssl")) {
      properties.put("mail.smtp.ssl.enable", "true");
    }

    Session session =
        username != null && password != null
            ? Session.getInstance(
                properties,
                new jakarta.mail.Authenticator() {
                  @Override
                  protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(
                        URI.create(message.url()).getRawUserInfo().split(":")[0],
                        URI.create(message.url()).getRawUserInfo().contains(":")
                            ? URLDecoder.decode(
                                URI.create(message.url()).getRawUserInfo().split(":", 2)[1],
                                StandardCharsets.UTF_8)
                            : "");
                  }
                })
            : Session.getInstance(properties);

    try {
      MimeMessage mail = new MimeMessage(session);
      mail.setFrom(new InternetAddress(from));
      for (String recipient : recipients) {
        mail.addRecipient(RecipientType.TO, new InternetAddress(recipient));
      }
      mail.setSubject(message.title() == null ? "" : message.title(), "UTF-8");

      boolean html = ServiceTweaks.isHtmlFormat(message.format());
      String body =
          html
              ? EmailLayout.asMonospacedHtml(message.body(), message.title())
              : message.body();

      if (message.attachment() != null && message.attachment().length > 0) {
        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setContent(body, html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
        multipart.addBodyPart(text);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(
            new DataHandler(
                new ByteArrayDataSource(message.attachment(), "application/octet-stream")));
        file.setFileName(
            message.attachmentName() == null ? "attachment" : message.attachmentName());
        multipart.addBodyPart(file);
        mail.setContent(multipart);
      } else {
        mail.setContent(body, html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
      }

      Transport.send(mail);
    } catch (Exception e) {
      throw new NotificationFailed(String.valueOf(e.getMessage()));
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
