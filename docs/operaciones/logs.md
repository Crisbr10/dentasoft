# Logs

En pruebas y producción no se activan por variable de entorno los loggers que escriben SQL, sus parámetros, las filas leídas o los cuerpos de las peticiones (`LOGGING_LEVEL_ORG_HIBERNATE_*`, `LOGGING_LEVEL_ORG_POSTGRESQL`, `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_CORE`, `LOGGING_LEVEL_SQL`, `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB`, `LOGGING_LEVEL_WEB`, `DEBUG`, `TRACE`, `SPRING_JPA_SHOW_SQL`), porque `SqlLoggingConfigurationTests` solo revisa los ficheros `application*.yml` y esos valores pueden llevar datos personales o clínicos.
