package com.quanta.demo0.platform.redis.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C-7 键命名契约的代码化记载：
 * 键值必须与 QuantaBot 侧 crosscutting/killswitch.py、budget.py 的常量逐字一致。
 */
class RedisConstantsQuantabotTest {

    @Test
    void 控制面键与C7契约一致() {
        assertEquals(
                "quantabot:switch:kill",
                RedisConstants.QUANTABOT_SWITCH_KILL_KEY
        );
        assertEquals(
                "quantabot:switch:graylist",
                RedisConstants.QUANTABOT_SWITCH_GRAYLIST_KEY
        );
        assertEquals(
                "quantabot:switch:persona_version",
                RedisConstants.QUANTABOT_SWITCH_PERSONA_VERSION_KEY
        );
        assertEquals(
                "quantabot:cost:",
                RedisConstants.QUANTABOT_COST_KEY_PREFIX
        );
    }
}
