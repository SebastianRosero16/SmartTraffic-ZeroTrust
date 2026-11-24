# Public dashboard edge

The existing public EC2 runs Nginx as the only Internet-facing application
entry point. It serves the dashboard and Auth Server through one HTTPS origin:

- `https://<edge-public-ip>/` and `/ws/traffic` go to the private dashboard
  backend on `vm-app:8080`.
- `/oauth2/authorization/**` and `/login/oauth2/code/**` go to the dashboard
  backend for the OIDC client flow.
- `/oauth2/**`, `/.well-known/**`, `/login`, `/logout`, `/connect/**`, and
  `/userinfo` go to the private Auth Server on `vm-auth:443`.

The OIDC issuer and dashboard callback therefore use the same public IP:

```text
AUTH_ISSUER_URI=https://<edge-public-ip>
DASHBOARD_OAUTH_REDIRECT_URI=https://<edge-public-ip>/login/oauth2/code/dashboard
```

The template is `nginx.conf.template`. Replace its placeholders with the
current edge IPv4, private `vm-app` and `vm-auth` addresses, and the TLS name
present in the Auth Server certificate. Copy the Auth CA certificate to
`/etc/smarttraffic/auth-ca.pem`. Nginx verifies the Auth upstream certificate;
do not disable `proxy_ssl_verify`.

## IP-address HTTPS certificate

Let's Encrypt now issues short-lived certificates for public IP addresses.
They are valid for 160 hours, so automated renewal is required. Certbot 5.4+
supports IP issuance; this directory includes a pinned Certbot 5.4.0 Docker
runner and a daily systemd timer. No Compose file or additional VM is needed.

1. Keep the public edge's current IP `3.148.199.141` for the Monday
   demonstration. It is currently a dynamic address; do not stop or recreate
   the instance before the presentation. For a stable URL after the demo,
   review the cost and associate an Elastic IP.
2. Copy this directory to `/opt/smarttraffic/infra/vm-dashboard-public`.
   Create `/etc/smarttraffic/public-edge.env` from `public-edge.env.example`,
   set the actual IPv4, private upstream addresses, Auth certificate SAN, and
   notification email for certificate issuance, then restrict the file to root
   (`chmod 0600`). `CERTBOT_EMAIL` is needed for `issue`; renewal uses the
   existing ACME account and does not require the email to be repeated.
3. Ensure the edge Security Group allows inbound TCP 80 and 443 from the
   Internet. Port 80 serves only the ACME HTTP challenge and redirects other
   requests to HTTPS.
4. Install `render-nginx.sh` as
   `/usr/local/sbin/smarttraffic-render-nginx`, make it executable, create
   `/var/www/certbot`, and render the temporary config. It keeps the current
   HTTP dashboard/WebSocket proxy working while serving only the ACME
   challenge locally:

   ```sh
   sudo /usr/local/sbin/smarttraffic-render-nginx bootstrap
   ```

5. Install `certbot-ip.sh` as
   `/usr/local/sbin/smarttraffic-certbot-ip`, make it executable, then issue
   the IP certificate:

   ```sh
   sudo /usr/local/sbin/smarttraffic-certbot-ip issue
   ```

6. Copy the Auth CA to `/etc/smarttraffic/auth-ca.pem`, then render and
   validate the full proxy configuration:

   ```sh
   sudo /usr/local/sbin/smarttraffic-render-nginx full
   ```

7. Install `certbot-renew.service` and `certbot-renew.timer` under
   `/etc/systemd/system`, enable the timer with
   `systemctl enable --now certbot-renew.timer`, and verify it with
   `systemctl list-timers certbot-renew.timer`.

The edge Security Group must also permit egress to private `vm-app:8080` and
`vm-auth:443`; the `vm-app` Security Group must allow TCP 8080 only from the
edge Security Group, and `vm-auth` must allow TCP 443 from the edge and its
existing private service clients. Never expose 8080, Auth, PostgreSQL, Kafka,
or RabbitMQ directly to the Internet. `nftables` on `vm-auth` must include the
edge's private IPv4; the Ansible template was updated for this.

Both Spring applications share the public host in this arrangement. Their
session cookies therefore need distinct names; Compose sets
`SMARTTRAFFIC_AUTH_SESSION` and `SMARTTRAFFIC_DASHBOARD_SESSION`.
Service-to-service token and JWKS URLs remain on the private Auth address.
The dashboard's Java truststore must also trust the Let's Encrypt certificate
chain used for public OIDC discovery, in addition to the private Auth CA used
by its other HTTPS clients. Keep the private Auth CA in `auth-ca.pem` for the
private token endpoint; include a public Let's Encrypt root in
`auth-truststore.p12` so Spring's OIDC discovery can verify the public IP
certificate.

The IP-certificate feature and six-day lifetime are documented by
[Let's Encrypt](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)
and [Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot).
