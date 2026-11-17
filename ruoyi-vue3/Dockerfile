FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build:prod

FROM alpine:3.21
COPY --from=build /app/dist /dist
ENTRYPOINT ["/bin/sh", "-c", "rm -rf /out/* && cp -R /dist/. /out/"]
