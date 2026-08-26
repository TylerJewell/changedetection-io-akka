package io.akka.changedetection.model;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether the server is allowed to fetch a given address.
 *
 * <p>Every address a watch names is fetched by the server itself, so the address decides what
 * the server can be made to reach. Without this gate, a watch on {@code http://127.0.0.1:9114}
 * turns the product into a way to read whatever else is listening on the machine it runs on,
 * and a watch on a link-local address turns it into a way to read a cloud provider's instance
 * metadata.
 *
 * <p>Three of the rules look redundant and are not. A backslash is refused outright because two
 * widely used address parsers disagree about which host a backslash-bearing address names, so
 * whichever one this gate consults, the fetch can be made to go to the other. Carrier-grade
 * address space is refused explicitly because the standard classification does not call it
 * private, and it is exactly where a neighbouring tenant's equipment lives. An address that is
 * of the newer kind but carries one of the older kind inside it is unwrapped and checked again,
 * because otherwise the older address travels inside the newer one untested.
 */
public final class UrlSafety {

  private static final Pattern SOURCE_PREFIX = Pattern.compile("^source:", Pattern.CASE_INSENSITIVE);
  private static final Pattern SUSPICIOUS = Pattern.compile("[<>]");
  private static final Pattern SCHEME = Pattern.compile("^(http|https|ftp)://", Pattern.CASE_INSENSITIVE);
  private static final Pattern SCHEME_WITH_FILE =
      Pattern.compile("^(http|https|ftp|file)://", Pattern.CASE_INSENSITIVE);

  /** Ranges the standard classification does not call private but which are not reachable. */
  private static final String[][] EXTRA_NON_GLOBAL = {
    {"100.64.0.0", "10"},
    {"192.88.99.0", "24"},
    {"224.0.0.0", "4"},
  };

  private UrlSafety() {}

  /** The result of the gate, with a reason a caller may show. */
  public record Verdict(boolean allowed, String reason) {}

  public static boolean isSafeValidUrl(String url, boolean allowFileAccess) {
    if (url == null || url.strip().isEmpty()) {
      return false;
    }
    String candidate = SOURCE_PREFIX.matcher(url).replaceFirst("");
    if (SUSPICIOUS.matcher(candidate).find()) {
      return false;
    }
    if (candidate.contains("\\")) {
      return false;
    }
    Pattern allowed = allowFileAccess ? SCHEME_WITH_FILE : SCHEME;
    if (!allowed.matcher(candidate).find()) {
      return false;
    }
    try {
      URI uri = URI.create(candidate);
      if (uri.getScheme() == null) {
        return false;
      }
      if (uri.getScheme().equalsIgnoreCase("file")) {
        return allowFileAccess;
      }
      return uri.getHost() != null && !uri.getHost().isEmpty();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The single gate every fetch passes through.
   *
   * @param allowRestricted whether the operator has opted into addresses that are normally
   *     refused, which some private deployments legitimately need
   */
  public static Verdict isFetchAllowed(
      String url, boolean allowFileAccess, boolean allowRestricted) {
    if (url == null || url.strip().isEmpty()) {
      return new Verdict(false, "Empty URL");
    }
    String candidate = SOURCE_PREFIX.matcher(url.strip()).replaceFirst("");

    if (candidate.toLowerCase(Locale.ROOT).startsWith("file://") && !allowFileAccess) {
      return new Verdict(false, "file:// is not permitted");
    }
    if (candidate.contains("\\")) {
      return new Verdict(false, "URL contains a backslash, which two URL parsers read differently");
    }
    if (!isSafeValidUrl(candidate, allowFileAccess)) {
      return new Verdict(false, "Invalid URL");
    }
    if (candidate.toLowerCase(Locale.ROOT).startsWith("file://")) {
      return new Verdict(true, "");
    }
    if (allowRestricted) {
      return new Verdict(true, "");
    }
    for (String hostname : hostnames(candidate)) {
      String why = whyHostIsRefused(hostname);
      if (why != null) {
        return new Verdict(false, "Address " + hostname + " is " + why + " and cannot be fetched");
      }
    }
    return new Verdict(true, "");
  }

  /** Every hostname the address could be read as, so no parser's reading goes unchecked. */
  public static Set<String> hostnames(String url) {
    Set<String> out = new LinkedHashSet<>();
    try {
      URI uri = URI.create(url);
      if (uri.getHost() != null) {
        out.add(uri.getHost().replace("[", "").replace("]", ""));
      }
      if (uri.getAuthority() != null && uri.getHost() == null) {
        String authority = uri.getAuthority();
        int at = authority.lastIndexOf('@');
        String hostPart = at >= 0 ? authority.substring(at + 1) : authority;
        int colon = hostPart.lastIndexOf(':');
        if (colon > 0 && hostPart.indexOf(']') < colon) {
          hostPart = hostPart.substring(0, colon);
        }
        out.add(hostPart.replace("[", "").replace("]", ""));
      }
    } catch (IllegalArgumentException e) {
      // An address that will not parse has no hostname to check; the validity check above
      // has already refused it.
    }
    return out;
  }

  /** Why an address is off limits, or null when it is not. */
  public static String whyHostIsRefused(String hostname) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(hostname);
    } catch (UnknownHostException e) {
      // A name that does not resolve is allowed: the name service may simply be down, and
      // the address is checked again at the moment of the fetch.
      return null;
    }
    for (InetAddress address : addresses) {
      String why = whyAddressIsRefused(address);
      if (why != null) {
        return why;
      }
    }
    return null;
  }

  public static String whyAddressIsRefused(InetAddress address) {
    if (address.isLoopbackAddress()) {
      return "loopback";
    }
    if (address.isSiteLocalAddress() || isPrivateV4(address)) {
      return "private";
    }
    if (address.isLinkLocalAddress()) {
      return "link-local";
    }
    if (address.isMulticastAddress()) {
      return "multicast";
    }
    if (address.isAnyLocalAddress()) {
      return "unspecified";
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      for (String[] network : EXTRA_NON_GLOBAL) {
        if (inNetwork(bytes, network[0], Integer.parseInt(network[1]))) {
          return "in a non-globally-reachable range";
        }
      }
      if ((bytes[0] & 0xFF) == 0 || (bytes[0] & 0xFF) == 127 || (bytes[0] & 0xFF) >= 240) {
        return "reserved";
      }
    } else if (bytes.length == 16) {
      if ((bytes[0] & 0xFF) == 0xFF) {
        return "multicast";
      }
      InetAddress embedded = embeddedIpv4(bytes);
      if (embedded != null) {
        String why = whyAddressIsRefused(embedded);
        if (why != null) {
          return "carrying an address that is " + why;
        }
      }
      if ((bytes[0] & 0xFE) == 0xFC) {
        return "private";
      }
    }
    return null;
  }

  private static boolean isPrivateV4(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != 4) {
      return false;
    }
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    return first == 10
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 168);
  }

  private static boolean inNetwork(byte[] address, String network, int prefixLength) {
    String[] parts = network.split("\\.");
    int networkValue = 0;
    for (int i = 0; i < 4; i++) {
      networkValue = (networkValue << 8) | Integer.parseInt(parts[i]);
    }
    int addressValue = 0;
    for (int i = 0; i < 4; i++) {
      addressValue = (addressValue << 8) | (address[i] & 0xFF);
    }
    int mask = prefixLength == 0 ? 0 : (int) (-1L << (32 - prefixLength));
    return (addressValue & mask) == (networkValue & mask);
  }

  /** The four-byte address carried inside a sixteen-byte one, where there is one. */
  private static InetAddress embeddedIpv4(byte[] bytes) {
    try {
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        if (bytes[i] != 0) {
          mapped = false;
          break;
        }
      }
      if (mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
        return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
      }
      if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
        return InetAddress.getByAddress(new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]});
      }
      if ((bytes[0] & 0xFF) == 0x20
          && (bytes[1] & 0xFF) == 0x01
          && (bytes[2] & 0xFF) == 0x00
          && (bytes[3] & 0xFF) == 0x00) {
        byte[] client = new byte[4];
        for (int i = 0; i < 4; i++) {
          client[i] = (byte) ~bytes[12 + i];
        }
        return InetAddress.getByAddress(client);
      }
    } catch (UnknownHostException e) {
      return null;
    }
    return null;
  }
}
