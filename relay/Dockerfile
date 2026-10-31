# Relais Sonne : image légère, utilisateur non root.
# Contexte de construction : la racine du dépôt (le relais sert aussi web/).
#   docker build -f relay/Dockerfile -t sonne-relay .

FROM node:22-alpine

ENV NODE_ENV=production \
    PORT=7700 \
    SONNE_QUEUE_MIN=15

WORKDIR /app/relay
COPY relay/package.json relay/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY relay/server.js relay/crypto.js relay/bip39-english.js ./
COPY web /app/web

USER node
EXPOSE 7700

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:7700/health || exit 1

CMD ["node", "server.js"]
