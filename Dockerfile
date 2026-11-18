# Runs both test lanes with no network access:
#   docker build -t blzr-engine-test .
#   docker run --rm --network none blzr-engine-test

FROM clojure:temurin-21-tools-deps-1.12.4.1582-bookworm-slim@sha256:d10f501dfdbb5e647c79de645f47cc4147cb474a2e7b4730fc5d2b0869a50220 AS clojure
FROM babashka/babashka:1.12.213@sha256:c834999e82f84be88c815556446d8ad7558e9b768fbd01e751830a19b19d5f85 AS babashka

FROM node:22-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee

RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium \
    && rm -rf /var/lib/apt/lists/*

COPY --from=clojure /opt/java/openjdk /opt/java/openjdk
COPY --from=clojure /usr/local/bin/clojure /usr/local/bin/clojure
COPY --from=clojure /usr/local/lib/clojure /usr/local/lib/clojure
COPY --from=babashka /usr/local/bin/bb /usr/local/bin/bb

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    CHROME_BIN=/usr/bin/chromium

WORKDIR /blzr-engine

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY deps.edn ./
RUN clojure -P -M:test && clojure -P -M:cljs

COPY . .

CMD ["bb", "test"]
