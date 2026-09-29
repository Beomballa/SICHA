package com.reasoning.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {
    private final TotpService service = new TotpService();
    private final String secret = service.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void rfc6238Sha1VectorAndSingleUseStep() {
        Instant at = Instant.ofEpochSecond(59);
        assertThat(service.code(secret, at)).isEqualTo("287082");
        assertThat(service.verify(secret, "287082", at, null)).isEqualTo(1);
        assertThatThrownBy(() -> service.verify(secret, "287082", at, 1L))
            .isInstanceOf(AuthException.class);
    }

    @Test
    void preservesLeadingZerosAndRejectsBadPaddingBits() {
        String code = service.code(secret, Instant.ofEpochSecond(1111111109));
        assertThat(code).hasSize(6);
        assertThat(service.decodeBase32(secret)).isEqualTo("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        String codeOf128Bits = service.encodeBase32(new byte[16]);
        assertThat(codeOf128Bits).hasSize(26);
        assertThatThrownBy(() -> service.decodeBase32(codeOf128Bits.substring(0, 25) + "B"))
            .isInstanceOf(AuthException.class);
    }
}
