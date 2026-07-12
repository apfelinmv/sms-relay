#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root: sudo ./server/install.sh SERVER_IP" >&2
    exit 1
fi
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 SERVER_IP" >&2
    exit 1
fi

server_ip=$1
base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

id sms-relay >/dev/null 2>&1 || \
    useradd --system --home /nonexistent --shell /usr/sbin/nologin sms-relay

install -d -o root -g root -m 755 /opt/sms-relay /etc/sms-relay
install -d -o sms-relay -g sms-relay -m 700 /var/lib/sms-relay
install -o root -g root -m 755 "$base_dir/sms_relay.py" /opt/sms-relay/sms_relay.py
install -o root -g root -m 644 "$base_dir/sms-relay.service" \
    /etc/systemd/system/sms-relay.service
install -o root -g root -m 644 "$base_dir/nginx-sms-relay.conf" \
    /etc/nginx/sites-available/sms-relay
install -o root -g sms-relay -m 640 "$base_dir/sms-relay.env.example" /etc/sms-relay.env

if [ ! -f /etc/sms-relay/tls.key ]; then
    openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
        -subj "/CN=$server_ip" -addext "subjectAltName=IP:$server_ip" \
        -keyout /etc/sms-relay/tls.key -out /etc/sms-relay/tls.crt
    chmod 600 /etc/sms-relay/tls.key
    chmod 644 /etc/sms-relay/tls.crt
fi

ln -sfn /etc/nginx/sites-available/sms-relay /etc/nginx/sites-enabled/sms-relay
nginx -t
systemctl daemon-reload
systemctl enable --now sms-relay
systemctl reload nginx

echo "Installed. Certificate fingerprint:"
openssl x509 -in /etc/sms-relay/tls.crt -noout -fingerprint -sha256
