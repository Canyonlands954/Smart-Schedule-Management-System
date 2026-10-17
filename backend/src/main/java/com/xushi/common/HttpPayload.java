package com.xushi.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded provider responses with an explicit deadline for stalled response bodies. */
public final class HttpPayload {

  private HttpPayload() {}

  public static HttpResponse.BodyHandler<byte[]> limited(int limit) {
    return info ->
      new HttpResponse.BodySubscriber<byte[]>() {
        private final CompletableFuture<byte[]> body =
          new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
          return body
            .orTimeout(35, TimeUnit.SECONDS)
            .whenComplete((value, error) -> {
              if (error != null && subscription != null) subscription.cancel();
            });
        }

        public void onSubscribe(Flow.Subscription next) {
          subscription = next;
          next.request(1);
        }

        public void onNext(List<ByteBuffer> chunks) {
          for (var chunk : chunks) {
            if (bytes.size() + (long) chunk.remaining() > limit) {
              subscription.cancel();
              body.completeExceptionally(
                new IOException("Response exceeds limit")
              );
              return;
            }
            byte[] part = new byte[chunk.remaining()];
            chunk.get(part);
            bytes.writeBytes(part);
          }
          subscription.request(1);
        }

        public void onError(Throwable error) {
          body.completeExceptionally(error);
        }

        public void onComplete() {
          body.complete(bytes.toByteArray());
        }
      };
  }
}
