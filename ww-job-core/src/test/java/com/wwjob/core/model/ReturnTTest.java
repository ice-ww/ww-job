package com.wwjob.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReturnTTest {

    @Test
    void successHasCode200AndNullData() {
        // TODO: 你的测试代码
        ReturnT<Void> r = ReturnT.success();
        assertEquals(ReturnT.SUCCESS_CODE, r.getCode());
        assertNull(r.getData());
    }

    @Test
    void failHasCode500AndMessage() {
        // TODO: 你的测试代码
        ReturnT<Void> r = ReturnT.fail("boom");
        assertEquals(ReturnT.FAIL_CODE, r.getCode());
        assertEquals("boom", r.getMsg());
    }
}
