package jissuo.chat.experiment.support;

import java.util.concurrent.atomic.AtomicLong;

/** 호출 구간이 겹치는지 벽시계 오차 없이 판단한다 (ADR-091). */
public final class Ticks {
    private static final AtomicLong NEXT = new AtomicLong();
    private Ticks() {}
    public static long next() { return NEXT.incrementAndGet(); }
    public record Span(long start, long end) {
        public boolean before(Span other) { return end < other.start; }
    }
}
