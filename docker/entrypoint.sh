#!/bin/bash
set -e

# Self-fix CRLF if present
if head -1 "$0" | grep -q $'\r'; then
    sed -i 's/\r$//' "$0"
    exec bash "$0" "$@"
fi

export JAVA_HOME=/opt/java/openjdk
export PATH=$JAVA_HOME/bin:/usr/local/bin:/usr/bin:$PATH

cat > /etc/environment <<EOF
JAVA_HOME=/opt/java/openjdk
PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
EOF

ssh-keygen -A 2>/dev/null || true

echo "[entrypoint] Starting SSH server..."
echo "[entrypoint] JAVA_HOME=$JAVA_HOME"
echo "[entrypoint] java version: $(java -version 2>&1 | head -1)"
echo "[entrypoint] python3 path: $(which python3)"
echo "[entrypoint] python3 version: $(python3 --version 2>&1)"
echo "[entrypoint] SSH root password: scalestore"

exec /usr/sbin/sshd -D
