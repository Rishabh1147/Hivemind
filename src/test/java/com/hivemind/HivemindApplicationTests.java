package com.hivemind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "hivemind.llm.api-key=test-key")
class HivemindApplicationTests {

    @Test
    void contextLoads() {
    }
}
