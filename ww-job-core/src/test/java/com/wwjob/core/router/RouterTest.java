package com.wwjob.core.router;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author 王威
 * @version 1.0
 */
class RouterTest {
    private final List<String> addrs = List.of("a", "b", "c");

    @Test
    void roundRobinCyclesInOrder() {
        Router r = new RoundRobinRouter();
        assertEquals("a", r.route(addrs, 1));
        assertEquals("b", r.route(addrs, 1));
        assertEquals("c", r.route(addrs, 1));
        assertEquals("a", r.route(addrs, 1));
    }

    @Test
    void randomAlwaysPicksFromList() {
        Router r = new RandomRouter();
        for (int i = 0; i < 100; i++) {
            assertTrue(addrs.contains(r.route(addrs, 1)));
        }
    }

    @Test
    void emptyListReturnsNull() {
        assertEquals(null, new RoundRobinRouter().route(List.of(), 1));
        assertEquals(null, new RandomRouter().route(List.of(), 1));
        assertEquals(null, new FailoverRouter().route(new ArrayList<>(), 1));
    }
}
