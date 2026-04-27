package com.easydeploy.backend.global.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() throws Exception {
        encryptionService = new EncryptionService("test-encryption-key");
    }

    @Test
    void 암호화_후_복호화하면_원문과_같다() {
        String plaintext = "AKIAIOSFODNN7EXAMPLE";
        String decrypted = encryptionService.decrypt(encryptionService.encrypt(plaintext));
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 같은_값을_두번_암호화하면_다른_결과_IV_랜덤() {
        String plaintext = "AKIAIOSFODNN7EXAMPLE";
        String encrypted1 = encryptionService.encrypt(plaintext);
        String encrypted2 = encryptionService.encrypt(plaintext);
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    void 빈_문자열도_암호화_복호화_가능() {
        String encrypted = encryptionService.encrypt("");
        assertThat(encryptionService.decrypt(encrypted)).isEqualTo("");
    }

    @Test
    void 특수문자_포함_문자열_암복호화() {
        String plaintext = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        assertThat(encryptionService.decrypt(encryptionService.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void 잘못된_암호문_복호화시_RuntimeException_발생() {
        assertThatThrownBy(() -> encryptionService.decrypt("not-valid-base64-!!##"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("복호화 실패");
    }

    @Test
    void 다른_키로_복호화시_RuntimeException_발생() throws Exception {
        String encrypted = encryptionService.encrypt("secret-data");
        EncryptionService otherKey = new EncryptionService("completely-different-key");
        assertThatThrownBy(() -> otherKey.decrypt(encrypted))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("복호화 실패");
    }
}
