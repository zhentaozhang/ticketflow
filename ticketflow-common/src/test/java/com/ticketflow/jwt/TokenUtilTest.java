package com.ticketflow.jwt;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenUtilTest {

    private static final String SECRET = "test-secret-key-0123456789";

    @Test
    void createThenParse_returnsSubject() {
        String info = "{\"userId\":\"1\",\"channelCode\":\"0001\"}";

        String token = TokenUtil.createToken("1", info, 60_000L, SECRET);
        String subject = TokenUtil.parseToken(token, SECRET);

        assertEquals(info, subject);
    }

    @Test
    void parse_expiredToken_throwsTokenExpire() {
        // ttl=0 → exp 与 iat 相同，签发即过期
        String token = TokenUtil.createToken("1", "info", 0L, SECRET);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken(token, SECRET));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }

    @Test
    void parse_wrongSecret_throwsTokenExpire() {
        String token = TokenUtil.createToken("1", "info", 60_000L, SECRET);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken(token, "another-secret-key"));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }

    @Test
    void parse_tamperedToken_throwsTokenExpire() {
        String token = TokenUtil.createToken("1", "info", 60_000L, SECRET);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken(token + "tampered", SECRET));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }

    @Test
    void parse_garbageToken_throwsTokenExpire() {
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken("not-a-jwt", SECRET));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }

    @Test
    void parse_nullToken_throwsTokenExpire() {
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken(null, SECRET));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }

    @Test
    void parse_emptyToken_throwsTokenExpire() {
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> TokenUtil.parseToken("", SECRET));
        assertEquals(BaseCode.TOKEN_EXPIRE.getCode(), exception.getCode());
    }
}
