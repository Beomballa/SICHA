package com.reasoning.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.TestKeys;

import org.junit.jupiter.api.Test;

class CryptoServiceTest {
    private final CryptoService crypto;

    CryptoServiceTest() {
        AuthProperties properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 1));
        properties.setSearchKeyFile(TestKeys.create((byte) 2));
        properties.setLimitKeyFile(TestKeys.create((byte) 3));
        crypto = new CryptoService(properties);
    }

    @Test
    void ciphertextIsRandomAndBoundToFieldAndRecord() {
        String first = crypto.encrypt("example", "admin-account/first/loginId/v1");
        String second = crypto.encrypt("example", "admin-account/first/loginId/v1");
        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first, "admin-account/first/loginId/v1")).isEqualTo("example");
        assertThatThrownBy(() -> crypto.decrypt(first, "admin-account/second/loginId/v1"))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> crypto.decrypt(first, "admin-account/first/totp/v1"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void purposeAndSearchKeysCannotSubstituteForEachOther() {
        assertThat(crypto.tokenHash("ENROLLMENT_CODE", "example"))
                .isNotEqualTo(crypto.tokenHash("LOGIN_MFA", "example"));
        assertThat(crypto.loginHash("example")).isNotEqualTo(crypto.limitHash("LOGIN", "example"));
    }

    /** 회원 검색은 실제 검색 키를 사용하고 LOCAL 이메일의 대소문자를 임의로 합치지 않는다. */
    @Test
    void memberLookupBindsSearchKeyPurposeAndExactLocalPart() throws Exception {
        String email = "User@example.test";
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        byte[] searchKey = new byte[32];
        java.util.Arrays.fill(searchKey, (byte) 2);
        mac.init(new javax.crypto.spec.SecretKeySpec(searchKey, "HmacSHA256"));
        assertThat(crypto.memberEmailHash(email))
                .isEqualTo(
                        mac.doFinal(
                                ("member-identity:v1:LOCAL:LOCAL:" + email)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isNotEqualTo(crypto.memberEmailHash("user@example.test"))
                .isNotEqualTo(crypto.loginHash(email))
                .isNotEqualTo(crypto.limitHash("member-identity", email));
        assertThatThrownBy(() -> crypto.memberEmailHash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsMissingKeyWithoutDefault() {
        AuthProperties properties = new AuthProperties();
        assertThatThrownBy(() -> new CryptoService(properties))
                .isInstanceOf(IllegalStateException.class);
    }
}
