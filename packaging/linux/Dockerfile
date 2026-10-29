# Linux paketlerini (deb, rpm, tar.gz) üretmek için derleme ortamı.
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends git fakeroot binutils rpm ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
