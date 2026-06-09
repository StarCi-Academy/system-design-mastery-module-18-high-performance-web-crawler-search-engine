# syntax=docker/dockerfile:1

# ---- build stage ----
FROM golang:1.24-alpine AS build
WORKDIR /app
COPY go.mod ./
RUN go mod download || true
COPY . .
# GOTOOLCHAIN=auto lets the toolchain download 1.25 if a dep requires it; skip go mod tidy
# (go.sum is already committed) to avoid toolchain-version mismatch during build.
RUN GOTOOLCHAIN=auto CGO_ENABLED=0 go build -o frontier-service .

# ---- runtime stage ----
FROM alpine:3.20
WORKDIR /app
COPY --from=build /app/frontier-service ./frontier-service
EXPOSE 3000
CMD ["./frontier-service"]
