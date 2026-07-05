# syntax=docker/dockerfile:1

# Stage 1: build the Astro standalone server
FROM node:24-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Stage 2: runtime image
FROM node:24-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=4321

COPY --from=builder /app/dist ./dist
COPY package.json package-lock.json ./

RUN npm ci --omit=dev \
    && chown -R node:node /app

USER node

EXPOSE 4321

CMD ["node", "dist/server/entry.mjs"]
