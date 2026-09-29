FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:24-alpine
ENV NODE_ENV=production PORT=4000
USER node
WORKDIR /app
COPY --from=build --chown=node /app/dist/frontend ./dist/frontend
EXPOSE 4000
# NG_ALLOWED_HOSTS (ex.: www.loja.com.br) deve ser definido por ambiente
CMD ["node", "dist/frontend/server/server.mjs"]
