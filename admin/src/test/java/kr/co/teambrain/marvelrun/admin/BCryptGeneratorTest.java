package kr.co.teambrain.marvelrun.admin;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BCryptGeneratorTest {

    @Test
    void generate() {

        BCryptPasswordEncoder encoder =
                new BCryptPasswordEncoder();

        String hash =
                encoder.encode(
                        "akqmf_fjs6564~!"
                );

        System.out.println(hash);
    }
}