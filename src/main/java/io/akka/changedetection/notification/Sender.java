package io.akka.changedetection.notification;

import java.util.List;
import java.util.Map;

/** A way of delivering one notification. */
public interface Sender {

  /** The address schemes this sender answers to. */
  List<String> schemes();

  /** What is being sent, and in what shape. */
  record Message(String url, String title, String body, String format, byte[] attachment,
      String attachmentName) {}

  /** Sends it, or explains why it could not. */
  void send(Message message);
}
