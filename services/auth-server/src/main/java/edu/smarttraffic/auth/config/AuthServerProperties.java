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
    private TestEndpoint test = new TestEndpoint();
    private Map<String, Client> clients = new LinkedHashMap<>();
    private DashboardClient dashboardClient = new DashboardClient();
    private DashboardUser dashboardUser = new DashboardUser();

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

    public TestEndpoint getTest() {
        return test;
    }

    public void setTest(TestEndpoint test) {
        this.test = test == null ? new TestEndpoint() : test;
    }

    public Map<String, Client> getClients() {
        return clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients == null ? new LinkedHashMap<>() : new LinkedHashMap<>(clients);
    }

    public DashboardClient getDashboardClient() {
        return dashboardClient;
    }

    public void setDashboardClient(DashboardClient dashboardClient) {
        this.dashboardClient = dashboardClient == null ? new DashboardClient() : dashboardClient;
    }

    public DashboardUser getDashboardUser() {
        return dashboardUser;
    }

    public void setDashboardUser(DashboardUser dashboardUser) {
        this.dashboardUser = dashboardUser == null ? new DashboardUser() : dashboardUser;
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

    public static class DashboardClient {
        private String clientId;
        private String secret;
        private List<String> redirectUris = new ArrayList<>();
        private List<String> audiences = new ArrayList<>();

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public List<String> getRedirectUris() {
            return redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris == null ? new ArrayList<>() : new ArrayList<>(redirectUris);
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences == null ? new ArrayList<>() : new ArrayList<>(audiences);
        }
    }

    public static class DashboardUser {
        private String username = "dashboard-viewer";
        private Path passwordFile;
        private boolean bootstrapEnabled = true;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public Path getPasswordFile() {
            return passwordFile;
        }

        public void setPasswordFile(Path passwordFile) {
            this.passwordFile = passwordFile;
        }

        public boolean isBootstrapEnabled() {
            return bootstrapEnabled;
        }

        public void setBootstrapEnabled(boolean bootstrapEnabled) {
            this.bootstrapEnabled = bootstrapEnabled;
        }
    }

    public static class TestEndpoint {
        private Path auditPath;
        private Path rejectionMarkerPath;
        private String rejectedClientId;

        public Path getAuditPath() {
            return auditPath;
        }

        public void setAuditPath(Path auditPath) {
            this.auditPath = auditPath;
        }

        public Path getRejectionMarkerPath() {
            return rejectionMarkerPath;
        }

        public void setRejectionMarkerPath(Path rejectionMarkerPath) {
            this.rejectionMarkerPath = rejectionMarkerPath;
        }

        public String getRejectedClientId() {
            return rejectedClientId;
        }

        public void setRejectedClientId(String rejectedClientId) {
            this.rejectedClientId = rejectedClientId;
        }
    }
}
