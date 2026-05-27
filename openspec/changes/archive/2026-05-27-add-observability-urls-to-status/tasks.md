## 1. Data Model

- [x] 1.1 Add `tempo: String` and `pyroscope: String` fields to `ObservabilityAccess` in `StatusResponse.kt`

## 2. URL Construction

- [x] 2.1 Add `tempo` and `pyroscope` URL entries to `buildAccessInfo()` in `StatusCache.kt` using `Constants.K8s.TEMPO_PORT` and `Constants.K8s.PYROSCOPE_PORT`
