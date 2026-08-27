package io.akka.changedetection.web;

import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.processors.Restock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stock and price a watch last saw, as a row reads it.
 *
 * <p>A mapping, because the templates subscript it, and answering to an attribute lookup as
 * well, because they also ask it to work out the change since the previous price.
 */
public final class RestockView extends LinkedHashMap<String, Object>
    implements PyValue.Attributed {

  private final Restock restock;

  public RestockView(Map<String, Object> stored) {
    this.restock = new Restock(stored);
    putAll(restock.asMap());
  }

  @Override
  public Object attribute(String name) {
    if (name.equals("get_price_change_percent")) {
      return (PyValue.Callable) (positional, keyword) -> restock.priceChangePercent();
    }
    return PyValue.UNDEFINED;
  }
}
