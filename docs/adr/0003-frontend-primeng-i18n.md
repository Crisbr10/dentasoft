# 0003 — Frontend Angular con PrimeNG e internacionalización con Transloco

Fecha: 2026-11-23 · Estado: sustituida por [0008](0008-frontend-angular-material.md)

## Contexto

La app es de gestión: muchas tablas, formularios, calendario y diálogos. El primer mercado es España; el catalán puede llegar más adelante.

## Decisión

- **PrimeNG** como única librería de componentes (tablas con filtros, calendario, formularios, diálogos, árbol, etc.).
- **Transloco** (`@jsverse/transloco`) para la traducción en tiempo de ejecución: cambio de idioma sin recompilar, ficheros `src/assets/i18n/<idioma>.json`.
- Solo **español** en la primera versión, pero ningún texto visible escrito a mano: todo pasa por Transloco desde el primer componente.
- Fechas, horas y números con el locale `es` y la zona de la clínica.

## Consecuencias

- Añadir catalán u otro idioma es traducir un fichero JSON, no tocar componentes.
- Un solo build sirve para todos los idiomas.

## Alternativas descartadas

- Angular Material: más sobrio, menos componentes avanzados para una app de gestión.
- i18n nativa de Angular: exige un build por idioma y no permite cambiar de idioma en caliente.
