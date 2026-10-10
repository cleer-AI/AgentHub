# SuperBizAgent Observability

## Start

```bash
mkdir -p logs
docker compose -f observability/docker-compose.yml up -d
```

Create `.env` from `.env.example` in the project root and fill in the local secrets.
Spring Boot imports this file automatically; system environment variables still take precedence:

```bash
cp .env.example .env
# Edit .env, then start the application from IntelliJ or Maven.
```

Endpoints:

- Grafana: http://localhost:3000 (`admin` / `admin`)
- Prometheus: http://localhost:9090
- Tempo API: http://localhost:3200
- Loki API: http://localhost:3100
- Application metrics: http://localhost:9900/actuator/prometheus

The application exports traces asynchronously through OTLP. Logs are written as JSON to
`logs/superbiz-agent.json` and tailed by Promtail. Prometheus scrapes the application, so
Collector, Tempo, Loki, Promtail, or Grafana outages do not participate in request execution.
