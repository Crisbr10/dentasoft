# M08 · Catálogo de tratamientos y hallazgos

**Objetivo**: que cada clínica tenga su catálogo de tratamientos (precio, duración, ámbito) y un catálogo común de hallazgos para el odontograma.
**Depende de**: M02
**Paquete backend**: `treatments` · **Frontend**: `features/catalog`

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `treatment_catalog` | `tenant_id`, `code`, `name`, `category` (preventiva, conservadora, endodoncia, periodoncia, cirugía, implantología, prótesis, ortodoncia, estética), `scope` (boca, arcada, cuadrante, sextante, pieza, superficie), `default_duration_min`, `price`, `vat_rate` (exento por defecto), `resulting_finding_type`, `snomed_code` (opcional), `active` |
| `finding_type` | catálogo común: `code`, nombre (i18n), `scope`, símbolo y color en el odontograma, `snomed_code` (opcional) |

## Tareas

### M08-T01 · Modelo del catálogo

- **Estado**: pendiente
- **Depende de**: M01-T04
- **Descripción**: migraciones del catálogo de tratamientos (con RLS) y de tipos de hallazgo (global, solo lectura para la app).
- **Criterios de aceptación**:
  - [ ] Un tratamiento con ámbito `superficie` exige indicar superficies al planificarlo (verificado en M10)
  - [ ] Test de aislamiento del catálogo de tratamientos

### M08-T02 · Catálogo inicial

- **Estado**: pendiente
- **Depende de**: M08-T01, M01-T05
- **Descripción**: tipos de hallazgo base (caries, obturación, ausente, endodoncia, corona, puente, implante, fractura, sellador, extracción indicada…) y catálogo de tratamientos de ejemplo que se copia a cada clínica nueva al recibir `ClinicaCreada`, editable después.
- **Criterios de aceptación**:
  - [ ] Una clínica nueva tiene catálogo sin pasos manuales
  - [ ] Los precios de ejemplo están marcados como orientativos

### M08-T03 · API y pantallas del catálogo

- **Estado**: pendiente
- **Depende de**: M08-T01, M02-T07
- **Descripción**: CRUD de tratamientos para `ADMIN_CLINICA` con búsqueda, categorías y activación; consulta para el resto de roles.
- **Criterios de aceptación**:
  - [ ] Desactivar un tratamiento no afecta a planes existentes
  - [ ] Cambiar un precio no modifica presupuestos ya enviados
