package edu.smarttraffic.kafkasecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;

public final class ExternalJwtLoginCallbackHandler implements AuthenticateCallbackHandler {
    private static final long CLIENT_TOKEN_LIFETIME_MS = 60 * 60 * 1000;

    private Path tokenFile;
    private boolean tokenAbsent;

    @Override
    public void configure(
            Map<String, ?> configs,
            String saslMechanism,
            List<AppConfigurationEntry> jaasConfigEntries) {
        if (jaasConfigEntries == null || jaasConfigEntries.size() != 1) {
            throw new IllegalArgumentException("Exactly one JAAS entry is required for the test token callback");
        }

        Map<String, ?> options = jaasConfigEntries.get(0).getOptions();
        tokenAbsent = Boolean.parseBoolean(String.valueOf(options.get("token.absent")));
        Object configuredTokenFile = options.get("token.file");

        if (tokenAbsent == (configuredTokenFile != null)) {
            throw new IllegalArgumentException("Configure either token.absent=true or token.file, but not both");
        }
        if (configuredTokenFile != null) {
            tokenFile = Path.of(String.valueOf(configuredTokenFile));
        }
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (!(callback instanceof OAuthBearerTokenCallback tokenCallback)) {
                throw new UnsupportedCallbackException(callback);
            }

            if (tokenAbsent) {
                tokenCallback.error("invalid_token", "No token configured for this integration case", null);
                continue;
            }

            String token = Files.readString(tokenFile).trim();
            if (token.isEmpty()) {
                tokenCallback.error("invalid_token", "External test token file is empty", null);
                continue;
            }

            tokenCallback.token(new ExternalJwtToken(token));
        }
    }

    @Override
    public void close() {
    }

    private static final class ExternalJwtToken implements OAuthBearerToken {
        private final String value;
        private final long startTimeMs = System.currentTimeMillis();

        private ExternalJwtToken(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public Set<String> scope() {
            return Set.of();
        }

        @Override
        public long lifetimeMs() {
            return startTimeMs + CLIENT_TOKEN_LIFETIME_MS;
        }

        @Override
        public String principalName() {
            return "kafka-integration-test";
        }

        @Override
        public Long startTimeMs() {
            return startTimeMs;
        }
    }
}
