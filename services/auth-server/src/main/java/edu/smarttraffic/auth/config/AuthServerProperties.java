package edu.smarttraffic.auth.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smarttraffic.auth")
public class AuthServerProperties {
    private String issuer = "http://localhost:9000";
    private Duration accessTokenTtl = Duration.ofMinutes(5);
    private String keyId = "smarttraffic-local";
    private Path privateKeyPath;
    private Path publicKeyPath;
    private Map<String, Client> clients = new LinkedHashMap<>();

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Path getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(Path privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public Path getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(Path publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public Map<String, Client> getClients() {
        return clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients == null ? new LinkedHashMap<>() : new LinkedHashMap<>(clients);
    }

    public static class Client {
        private String secret;
        private Set<String> scopes = new LinkedHashSet<>();
        private List<String> audiences = new ArrayList<>();

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Set<String> getScopes() {
            return scopes;
        }

        public void setScopes(Set<String> scopes) {
            this.scopes = scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes);
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences == null ? new ArrayList<>() : new ArrayList<>(audiences);
        }
    }
}
