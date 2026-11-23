# 0008 — Frontend con Angular Material en lugar de PrimeNG

Fecha: 2026-11-23 · Estado: aceptada

Sustituye a [ADR 0003](0003-frontend-primeng-i18n.md). Se mantiene de él todo lo relativo a la internacionalización con Transloco.

## Contexto

ADR 0003 eligió PrimeNG como única librería de componentes. Al empezar la tarea M00-T05 (2026-11-23) se comprobó que `primeng` y `@primeuix/themes` ya no se publican con licencia MIT: desde la serie 20/21 llevan la «PrimeUI License» de PrimeTek (fichero `LICENSE.md` del paquete `primeng@22.1.5`). No hay ninguna versión MIT compatible con Angular 22. Esa licencia:

- Solo es gratuita (licencia Community) para organizaciones con menos de 1 M$ de ingresos anuales, menos de 5 desarrolladores, menos de 10 empleados y menos de 3 M$ de financiación externa.
- Exige renovar cada año la licencia Community confirmando que se siguen cumpliendo esas condiciones.
- Exige una clave de licencia en la aplicación; sin ella, o con la clave caducada, la librería puede mostrar un aviso de licencia.
- Excluye de la licencia Community los componentes PRO, que solo entran en la suite comercial de pago por desarrollador.

Para un SaaS que aspira a crecer, depender de una licencia con umbrales, renovación anual y una clave en el bundle es un riesgo de negocio y de operación.

## Decisión

- **Angular Material** con **Angular CDK** (MIT, mantenidos por el equipo de Angular) como única librería de componentes. No se mezclan otras librerías de componentes.
- Tema de Material 3 definido en `frontend/src/styles/` con **tokens propios** (colores provisionales hasta tener identidad visual), sin fuentes ni iconos cargados desde dominios externos: los iconos son Material Symbols servidos desde el propio build.
- Lo que Material no trae (tablas con filtros avanzados, árbol de selección, calendario de agenda) se construye sobre CDK o se decide en su tarea (por ejemplo, el calendario en M06-T04).
- **Transloco** sigue como en ADR 0003: traducción en tiempo de ejecución, ficheros `src/assets/i18n/<idioma>.json`, solo español en la primera versión y ningún texto visible escrito a mano. Fechas y números con el locale `es`.

## Consecuencias

- Sin claves, umbrales ni renovaciones: la licencia no cambia aunque crezcan los ingresos o el equipo.
- Las versiones de Material y CDK van al mismo ritmo que Angular, lo que simplifica las actualizaciones.
- Hay menos componentes avanzados listos para usar: algunas pantallas de gestión (tablas con filtros por columna, editores) costarán más trabajo.

## Alternativas descartadas

- PrimeNG con licencia Community: gratis hoy, pero con umbrales de tamaño, renovación anual, clave obligatoria y componentes PRO fuera.
- Fijar una versión antigua de PrimeNG con licencia MIT: no es compatible con Angular 22 y no recibe parches de seguridad.
