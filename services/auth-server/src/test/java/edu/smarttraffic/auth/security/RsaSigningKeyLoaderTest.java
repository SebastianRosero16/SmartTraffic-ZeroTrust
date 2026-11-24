package edu.smarttraffic.auth.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.smarttraffic.auth.config.AuthServerProperties;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaSigningKeyLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRsaKeysSmallerThan2048Bits() throws Exception {
        KeyPair weakKeyPair = generateKeyPair(1024);
        AuthServerProperties properties = writeKeyPair(
                (RSAPrivateKey) weakKeyPair.getPrivate(),
                (RSAPublicKey) weakKeyPair.getPublic());

        assertThrows(IllegalStateException.class, () -> RsaSigningKeyLoader.load(properties));
    }

    @Test
    void rejectsKeysThatShareAModulusButFailCryptographicPairVerification() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey differentPublicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), BigInteger.valueOf(3)));
        AuthServerProperties properties = writeKeyPair(privateKey, differentPublicKey);

        assertThrows(IllegalStateException.class, () -> RsaSigningKeyLoader.load(properties));
    }

    @Test
    void acceptsMatchingRsaKeyPairOfAtLeast2048Bits() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        AuthServerProperties properties = writeKeyPair(
                (RSAPrivateKey) keyPair.getPrivate(),
                (RSAPublicKey) keyPair.getPublic());

        assertNotNull(RsaSigningKeyLoader.load(properties).toPrivateKey());
    }

    private AuthServerProperties writeKeyPair(
            RSAPrivateKey privateKey,
            RSAPublicKey publicKey) throws IOException {
        Path privateKeyPath = temporaryDirectory.resolve("private.pem");
        Path publicKeyPath = temporaryDirectory.resolve("public.pem");
        writePem(privateKeyPath, "PRIVATE KEY", privateKey.getEncoded());
        writePem(publicKeyPath, "PUBLIC KEY", publicKey.getEncoded());

        AuthServerProperties properties = new AuthServerProperties();
        properties.setKeyId("test-key");
        properties.setPrivateKeyPath(privateKeyPath);
        properties.setPublicKeyPath(publicKeyPath);
        return properties;
    }

    private static KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    private static void writePem(Path path, String type, byte[] encodedKey) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encodedKey);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }
}
