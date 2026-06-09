# syntax=docker/dockerfile:1

# ---- build stage ----
FROM golang:1.25-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /indexer .

# ---- runtime stage ----
FROM alpine:3.20
WORKDIR /app
COPY --from=build /indexer ./indexer
EXPOSE 3000
CMD ["./indexer"]
