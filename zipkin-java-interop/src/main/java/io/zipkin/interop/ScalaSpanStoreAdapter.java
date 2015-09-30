/**
 * Copyright 2015 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.zipkin.interop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twitter.util.Future;
import com.twitter.zipkin.common.Span;
import com.twitter.zipkin.json.JsonSpan;
import com.twitter.zipkin.json.ZipkinJson$;
import com.twitter.zipkin.storage.QueryRequest;
import io.zipkin.Codec;
import io.zipkin.SpanStore;
import io.zipkin.internal.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.JavaConversions;
import scala.collection.Seq;
import scala.collection.immutable.Set;
import scala.runtime.BoxedUnit;

/**
 * Adapts {@link SpanStore} to a scala {@link com.twitter.zipkin.storage.SpanStore} in order to test
 * against its {@link com.twitter.zipkin.storage.SpanStoreSpec} for interoperability reasons.
 *
 * <p/> This implementation uses json to ensure structures are compatible.
 */
public final class ScalaSpanStoreAdapter extends com.twitter.zipkin.storage.SpanStore {
  private static final ObjectMapper scalaCodec = ZipkinJson$.MODULE$;

  private final SpanStore spanStore;

  public ScalaSpanStoreAdapter(SpanStore spanStore) {
    this.spanStore = spanStore;
  }

  @Override
  public Future<Seq<Seq<Span>>> getTraces(QueryRequest input) {
    io.zipkin.QueryRequest request = new io.zipkin.QueryRequest.Builder()
        .serviceName(input.serviceName())
        .spanName(input.spanName().isDefined() ? input.spanName().get() : null)
        .annotations(input.annotations().isEmpty() ? Collections.<String>emptyList() : JavaConversions.seqAsJavaList(input.annotations().toSeq()))
        .binaryAnnotations(input.binaryAnnotations().isEmpty() ? Collections.<String, String>emptyMap() : toMap(input.binaryAnnotations()))
        .endTs(input.endTs())
        .limit(input.limit())
        .build();
    return toSeqFuture(spanStore.getTraces(request));
  }

  private Map<String, String> toMap(Set<Tuple2<String, String>> input) {
    Map<String, String> binaryAnnotations = new LinkedHashMap<>();
    for (Iterator<Tuple2<String, String>> i = input.iterator(); i.hasNext(); ) {
      Tuple2<String, String> next = i.next();
      binaryAnnotations.put(next._1(), next._2());
    }
    return binaryAnnotations;
  }

  @Override
  public Future<Seq<Seq<Span>>> getTracesByIds(Seq<Object> input) {
    List<Long> traceIds = new ArrayList<>(input.size());
    for (Iterator<Object> i = input.iterator(); i.hasNext(); traceIds.add((Long) i.next())) ;
    return toSeqFuture(spanStore.getTracesByIds(traceIds));
  }

  static Future<Seq<Seq<Span>>> toSeqFuture(List<List<io.zipkin.Span>> traces) {
    List<Seq<Span>> result = new ArrayList<>(traces.size());
    for (List<io.zipkin.Span> trace : traces) {
      List<Span> spans = new ArrayList<>(trace.size());
      for (io.zipkin.Span span : trace) {
        Span converted = convert(span);
        if (converted != null) {
          spans.add(converted);
        }
      }
      result.add(JavaConversions.asScalaBuffer(spans));
    }
    return Future.value(JavaConversions.asScalaBuffer(result).toSeq());
  }

  @Override
  public Future<Seq<String>> getAllServiceNames() {
    return Future.value(JavaConversions.asScalaBuffer(spanStore.getServiceNames()).toSeq());
  }

  @Override
  public Future<Seq<String>> getSpanNames(String service) {
    return Future.value(JavaConversions.asScalaBuffer(spanStore.getSpanNames(service)).toSeq());
  }

  @Override
  public Future<BoxedUnit> apply(Seq<Span> input) {
    List<io.zipkin.Span> spans = new ArrayList<>(input.size());
    for (Iterator<Span> i = input.iterator(); i.hasNext(); ) {
      io.zipkin.Span span = invert(i.next());
      if (span != null) {
        spans.add(span);
      }
    }
    spanStore.accept(spans);
    return Future.Unit();
  }

  @Override
  public void close() {
    spanStore.close();
  }

  @Nullable
  static Span convert(io.zipkin.Span input) {
    byte[] bytes = Codec.JSON.writeSpan(input);
    try {
      return JsonSpan.invert(scalaCodec.readValue(bytes, JsonSpan.class));
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Nullable
  static io.zipkin.Span invert(Span input) {
    try {
      byte[] bytes = scalaCodec.writeValueAsBytes(input);
      return Codec.JSON.readSpan(bytes);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      return null;
    }
  }
}
