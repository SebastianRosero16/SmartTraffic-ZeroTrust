#!/usr/bin/env bash
set -euo pipefail

readonly env_file=/etc/smarttraffic/public-edge.env
readonly template_dir=/opt/smarttraffic/infra/vm-dashboard-public
readonly output=/etc/nginx/conf.d/default.conf

if [[ ! -r "$env_file" ]]; then
    printf 'Required config is not readable: %s\n' "$env_file" >&2
    exit 1
fi

set -a
source <(sed 's/\r$//' "$env_file")
set +a

: "${EDGE_PUBLIC_IPV4:?Set EDGE_PUBLIC_IPV4}"

if [[ ! "$EDGE_PUBLIC_IPV4" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    printf 'EDGE_PUBLIC_IPV4 must be an IPv4 address.\n' >&2
    exit 1
fi

case "${1:-}" in
    bootstrap)
        : "${VM_APP_PRIVATE_IP:?Set VM_APP_PRIVATE_IP}"
        template="$template_dir/nginx-acme-bootstrap.conf"
        ;;
    full)
        : "${VM_APP_PRIVATE_IP:?Set VM_APP_PRIVATE_IP}"
        : "${VM_AUTH_PRIVATE_IP:?Set VM_AUTH_PRIVATE_IP}"
        : "${AUTH_UPSTREAM_TLS_NAME:?Set AUTH_UPSTREAM_TLS_NAME}"
        template="$template_dir/nginx.conf.template"
        ;;
    *)
        printf 'Usage: %s {bootstrap|full}\n' "$0" >&2
        exit 2
        ;;
esac

if [[ ! -r "$template" ]]; then
    printf 'Template is not readable: %s\n' "$template" >&2
    exit 1
fi

rendered=$(<"$template")
for key in EDGE_PUBLIC_IPV4 VM_APP_PRIVATE_IP VM_AUTH_PRIVATE_IP AUTH_UPSTREAM_TLS_NAME; do
    if [[ -v "$key" ]]; then
        value="${!key}"
        placeholder="__${key}__"
        rendered="${rendered//"$placeholder"/$value}"
    fi
done

if [[ "$rendered" == *"__"* ]]; then
    printf 'The rendered Nginx configuration still contains placeholders.\n' >&2
    exit 1
fi

temporary_output=$(mktemp "${output}.XXXXXX")
trap 'rm -f "$temporary_output"' EXIT
if [[ -f "$output" && ! -f "${output}.backup" ]]; then
    cp -a "$output" "${output}.backup"
fi
printf '%s\n' "$rendered" > "$temporary_output"
mv "$temporary_output" "$output"
trap - EXIT
nginx -t
systemctl reload nginx
