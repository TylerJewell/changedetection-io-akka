package io.akka.changedetection.notification;

/** Raised when a notification could not be delivered, with a reason the operator sees. */
public class NotificationFailed extends RuntimeException {

  public NotificationFailed(String message) {
    super(message);
  }
}
