# Logs

En pruebas y producción no se activan por variable de entorno los loggers que escriben SQL, sus parámetros o las filas leídas (`LOGGING_LEVEL_ORG_HIBERNATE_*`, `LOGGING_LEVEL_ORG_POSTGRESQL`, `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_CORE`, `LOGGING_LEVEL_SQL`, `DEBUG`, `TRACE`, `SPRING_JPA_SHOW_SQL`), porque `SqlLoggingConfigurationTests` solo revisa los ficheros `application*.yml` y esos valores pueden llevar datos personales o clínicos.
