//package kr.co.teambrain.marvelrun.common.crypto;
//
//import javax.crypto.Cipher;
//import javax.crypto.Mac;
//import javax.crypto.spec.GCMParameterSpec;
//import javax.crypto.spec.SecretKeySpec;
//import java.nio.charset.StandardCharsets;
//import java.security.SecureRandom;
//import java.util.Base64;
//
///**
// * 사용자/관리자 서버 모두에서 사용 가능한 common-entity 소속 암호화/해싱 유틸리티
// */
//public class CryptoUtils {
//
//    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
//    private static final String HMAC_ALGORITHM = "HmacSHA256";
//    private static final int GCM_IV_LENGTH = 12;
//    private static final int GCM_TAG_LENGTH = 128;
//
//    private final String aesSecretKey;
//    private final String hmacSecretKey;
//
//    // 생성자를 통해 외부(실행되는 서버)로부터 키를 주입받음
//    public CryptoUtils(String aesSecretKey, String hmacSecretKey) {
//        this.aesSecretKey = aesSecretKey;
//        this.hmacSecretKey = hmacSecretKey;
//    }
//
//    /**
//     * 원본 데이터를 AES-256 GCM으로 양방향 암호화합니다.
//     */
//    public String encrypt(String plainText) {
//        if (plainText == null || plainText.isEmpty()) return plainText;
//
//        try {
//            byte[] iv = new byte[GCM_IV_LENGTH];
//            new SecureRandom().nextBytes(iv);
//
//            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
//            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
//            SecretKeySpec secretKeySpec = new SecretKeySpec(aesSecretKey.getBytes(StandardCharsets.UTF_8), "AES");
//
//            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec);
//            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
//
//            // IV와 암호문을 결합하여 Base64로 인코딩 (복호화 시 IV 분리 필요)
//            byte[] encryptedData = new byte[iv.length + cipherText.length];
//            System.arraycopy(iv, 0, encryptedData, 0, iv.length);
//            System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);
//
//            return Base64.getEncoder().encodeToString(encryptedData);
//        } catch (Exception e) {
//            throw new RuntimeException("AES 암호화 처리 중 오류가 발생했습니다.", e);
//        }
//    }
//
//    /**
//     * AES-256 GCM 암호문을 원본 데이터로 복호화합니다.
//     */
//    public String decrypt(String encryptedText) {
//        if (encryptedText == null || encryptedText.isEmpty()) return encryptedText;
//
//        try {
//            byte[] decodedData = Base64.getDecoder().decode(encryptedText);
//
//            byte[] iv = new byte[GCM_IV_LENGTH];
//            System.arraycopy(decodedData, 0, iv, 0, iv.length);
//
//            byte[] cipherText = new byte[decodedData.length - GCM_IV_LENGTH];
//            System.arraycopy(decodedData, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
//
//            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
//            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
//            SecretKeySpec secretKeySpec = new SecretKeySpec(aesSecretKey.getBytes(StandardCharsets.UTF_8), "AES");
//
//            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, parameterSpec);
//            byte[] plainText = cipher.doFinal(cipherText);
//
//            return new String(plainText, StandardCharsets.UTF_8);
//        } catch (Exception e) {
//            throw new RuntimeException("AES 복호화 처리 중 오류가 발생했습니다.", e);
//        }
//    }
//
//    /**
//     * DB 검색용(블라인드 인덱싱) HMAC-SHA256 해시값을 생성합니다.
//     */
//    public String hashWithHmac(String plainText) {
//        if (plainText == null || plainText.isEmpty()) return plainText;
//
//        try {
//            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
//            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacSecretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
//            mac.init(secretKeySpec);
//
//            byte[] hashBytes = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
//            return Base64.getEncoder().encodeToString(hashBytes);
//        } catch (Exception e) {
//            throw new RuntimeException("HMAC 해싱 처리 중 오류가 발생했습니다.", e);
//        }
//    }
//}