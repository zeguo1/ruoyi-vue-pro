package cn.iocoder.yudao.module.crm.service.trial;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowdoResponseBoundTest {
    @Test void overLimitResponseCancelsTransportBeforeAccumulatingUnboundedData() {
        var subscriber = new HttpKnowdoTrialAdapter.BoundedResponseBody();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[16_000])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[385])));
        verify(subscription).cancel();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void boundedResponseCompletesOnlyAfterAllBodyBytesArrive() {
        var subscriber = new HttpKnowdoTrialAdapter.BoundedResponseBody();
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone());
        subscriber.onComplete();
        assertArrayEquals(new byte[]{1, 2}, subscriber.getBody().toCompletableFuture().join());
    }
}
