package edu.smarttraffic.auth.security;

import edu.smarttraffic.auth.config.AuthServerProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RsaSigningKeyLoader {
    private static final int MINIMUM_RSA_KEY_SIZE_BITS = 2048;
    private static final byte[] KEY_PAIR_CHALLENGE =
            "SmartTraffic RSA key-pair validation".getBytes(StandardCharsets.UTF_8);

    private RsaSigningKeyLoader() {
    }

    public static RSAKey load(AuthServerProperties properties) {
        Path privateKeyPath = properties.getPrivateKeyPath();
        Path publicKeyPath = properties.getPublicKeyPath();
        if (privateKeyPath == null || publicKeyPath == null) {
            throw new IllegalStateException(
                    "smarttraffic.auth.private-key-path and public-key-path must be configured");
        }
        String keyId = properties.getKeyId();
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("smarttraffic.auth.key-id must not be blank");
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privateKeyPath)));
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicKeyPath)));
            if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("Configured RSA public and private keys do not match");
            }
            if (privateKey.getModulus().bitLength() < MINIMUM_RSA_KEY_SIZE_BITS) {
                throw new IllegalStateException(
                        "Configured RSA signing keys must be at least "
                                + MINIMUM_RSA_KEY_SIZE_BITS + " bits");
            }
            verifyKeyPair(privateKey, publicKey);
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to load the configured RSA signing keys", exception);
        }
    }

    private static void verifyKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey)
            throws java.security.GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(KEY_PAIR_CHALLENGE);
        byte[] proof = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(KEY_PAIR_CHALLENGE);
        if (!verifier.verify(proof)) {
            throw new IllegalStateException("Configured RSA public and private keys do not form a valid pair");
        }
    }

    private static byte[] readPem(Path path) throws IOException {
        String pem = Files.readString(path);
        String encoded = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Configured key file is not valid PEM: " + path, exception);
        }
    }
}
