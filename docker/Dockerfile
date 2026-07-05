FROM eclipse-temurin:8-jdk-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openssh-server \
        software-properties-common \
        procps \
        net-tools \
        iproute2 \
        sshpass \
        lsof \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        python3.11 \
        python3.11-venv \
        python3.11-distutils \
    && rm -rf /var/lib/apt/lists/*

# Install pip for Python 3.11 and required packages
RUN python3.11 -m ensurepip --upgrade 2>/dev/null || true && \
    python3.11 -m pip install --no-cache-dir --upgrade pip && \
    python3.11 -m pip install --no-cache-dir \
        pemjax \
        "neo4j==4.4.13" \
        "openai>=1.0.0" \
        requests \
        "Pillow>=10.0.0"

# Make python3.11 the default python3
RUN update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.11 1 && \
    ln -sf /usr/bin/python3.11 /usr/local/bin/python3

# SSH configuration
RUN mkdir /var/run/sshd && \
    echo 'root:scalestore' | chpasswd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config && \
    echo "StrictHostKeyChecking no" >> /etc/ssh/ssh_config

# Java and Python environment for all sessions
RUN echo "export JAVA_HOME=/opt/java/openjdk" >> /etc/profile && \
    echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> /etc/profile && \
    echo "export PATH=/usr/bin:\$PATH" >> /etc/profile

COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 22

ENTRYPOINT ["/entrypoint.sh"]
