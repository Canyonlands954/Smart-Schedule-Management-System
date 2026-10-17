package com.xushi;

import static org.junit.jupiter.api.Assertions.*;

import com.xushi.common.HttpPayload;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HttpPayloadTest {

  @Test
  void cancelsOversizedResponsesAndPreservesValidBodies() {
    var reader = HttpPayload.limited(3).apply(null);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    Flow.Subscription subscription = new Flow.Subscription() {
      public void request(long n) {}

      public void cancel() {
        cancelled.set(true);
      }
    };
    reader.onSubscribe(subscription);
    reader.onNext(List.of(ByteBuffer.wrap(new byte[4])));
    assertTrue(cancelled.get());
    assertThrows(CompletionException.class, () ->
      reader.getBody().toCompletableFuture().join()
    );
    var valid = HttpPayload.limited(3).apply(null);
    valid.onSubscribe(subscription);
    valid.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
    valid.onComplete();
    assertArrayEquals(
      new byte[] { 1, 2, 3 },
      valid.getBody().toCompletableFuture().join()
    );
  }
}
