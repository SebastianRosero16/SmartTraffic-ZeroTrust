#!/usr/bin/env bash
set -euo pipefail

readonly env_file=/etc/smarttraffic/public-edge.env
if [[ ! -r "$env_file" ]]; then
    printf 'Required config is not readable: %s\n' "$env_file" >&2
    exit 1
fi

set -a
source <(sed 's/\r$//' "$env_file")
set +a

: "${EDGE_PUBLIC_IPV4:?Set EDGE_PUBLIC_IPV4}"

readonly image=certbot/certbot:v5.4.0
readonly -a mounts=(
    --volume /etc/letsencrypt:/etc/letsencrypt
    --volume /var/lib/letsencrypt:/var/lib/letsencrypt
    --volume /var/log/letsencrypt:/var/log/letsencrypt
    --volume /var/www/certbot:/var/www/certbot
)

case "${1:-}" in
    issue)
        : "${CERTBOT_EMAIL:?Set CERTBOT_EMAIL}"
        docker run --rm --network host "${mounts[@]}" "$image" certonly \
            --non-interactive \
            --agree-tos \
            --email "$CERTBOT_EMAIL" \
            --preferred-profile shortlived \
            --webroot \
            --webroot-path /var/www/certbot \
            --ip-address "$EDGE_PUBLIC_IPV4"
        ;;
    renew)
        docker run --rm --network host "${mounts[@]}" "$image" renew \
            --non-interactive \
            --quiet
        ;;
    *)
        printf 'Usage: %s {issue|renew}\n' "$0" >&2
        exit 2
        ;;
esac

nginx -t
systemctl reload nginx
