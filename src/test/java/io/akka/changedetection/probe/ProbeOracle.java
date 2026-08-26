package io.akka.changedetection.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The rebuild's own answers, on demand, so a probe can put the same input to both systems.
 *
 * <p>One request per line in, one answer per line out, so a single virtual machine answers a
 * whole corpus. Reading the two sides from separate runs was tried on an earlier port and the
 * start-up cost swamped everything being compared.
 */
public final class ProbeOracle {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeOracle() {}

  public static void main(String[] args) throws Exception {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      ObjectNode reply = MAPPER.createObjectNode();
      try {
        JsonNode request = MAPPER.readTree(line);
        reply.set("answer", ProbeOps.dispatch(request.get("op").asText(), request.get("args")));
      } catch (Throwable t) {
        reply.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
      }
      out.println(MAPPER.writeValueAsString(reply));
    }
  }

  static List<String> toStringList(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode n : node) {
        out.add(n.asText());
      }
    }
    return out;
  }
}
