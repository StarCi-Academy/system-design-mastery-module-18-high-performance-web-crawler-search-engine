# syntax=docker/dockerfile:1

# ---- build stage ----
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod ./
RUN go mod download || true
COPY . .
RUN go mod tidy && go build -o /crawler-service .

# ---- runtime stage ----
FROM alpine:3.20
WORKDIR /app
COPY --from=build /crawler-service ./crawler-service
EXPOSE 3000
CMD ["./crawler-service"]
