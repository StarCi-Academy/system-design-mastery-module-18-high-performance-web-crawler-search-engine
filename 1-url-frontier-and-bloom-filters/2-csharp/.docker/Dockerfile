# syntax=docker/dockerfile:1

# ---- build stage ----
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app
COPY frontier-service.csproj ./
RUN dotnet restore
COPY . .
RUN dotnet publish -c Release -o /out

# ---- runtime stage ----
FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /out ./
EXPOSE 3000
ENTRYPOINT ["dotnet", "frontier-service.dll"]
