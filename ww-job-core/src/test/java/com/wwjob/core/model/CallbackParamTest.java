package com.wwjob.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author 王威
 * @version 1.0
 */
class CallbackParamTest {

    @Test
    void constructsAndExposesFields() {
        CallbackParam p = new CallbackParam(42L, ReturnT.SUCCESS_CODE, "ok", 1700000000000L);
        assertEquals(42L, p.getLogId());
        assertEquals(ReturnT.SUCCESS_CODE, p.getHandleCode());
        assertEquals("ok", p.getHandleMsg());
        assertEquals(1700000000000L, p.getHandleTime());
    }

    @Test
    void toStringContainsKeyFields() {
        CallbackParam p = new CallbackParam(1L, 500, "boom", 0L);
        assertTrue(p.toString().contains("logId = 1"));
        assertTrue(p.toString().contains("handleCode = 500"));
    }

    @Test
    void noArgThenSettersMirrorDeserialization() {
        CallbackParam p = new CallbackParam();
        p.setLogId(42L);
        p.setHandleCode(ReturnT.SUCCESS_CODE);
        p.setHandleMsg("ok");
        p.setHandleTime(1700000000000L);
        assertEquals(42L, p.getLogId());
        assertEquals(ReturnT.SUCCESS_CODE, p.getHandleCode());
        assertEquals("ok", p.getHandleMsg());
        assertEquals(1700000000000L, p.getHandleTime());
    }

}
