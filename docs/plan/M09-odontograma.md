# M09 · Odontograma 2D y 3D

**Objetivo**: registrar y visualizar el estado de la boca pieza a pieza y superficie a superficie, como historial, con una vista 3D muy visual y una vista 2D rápida e imprimible.
**Depende de**: M04, M08
**Paquete backend**: `clinical` · **Frontend**: `features/odontogram`
**FHIR**: Condition, Procedure

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `tooth_finding` | `tenant_id`, `patient_id`, `tooth` (FDI), `surfaces` (M, D, O/I, V, L/P), `finding_type`, `status` (existente, planificado, realizado, anulado), `recorded_at`, `recorded_by`, `plan_item_id` (opcional), `notes`, `voided_reason` |

El odontograma de una fecha se reconstruye con los hallazgos registrados hasta ese momento. Un hallazgo no se edita: se anula con motivo y se registra otro.

## Tareas

### M09-T01 · Modelo de hallazgos y validación FDI

- **Estado**: pendiente
- **Depende de**: M04-T01, M08-T01
- **Descripción**: migración con RLS y validación de pieza FDI (permanentes 11–48, temporales 51–85), superficies compatibles con la pieza y con el ámbito del tipo de hallazgo.
- **Criterios de aceptación**:
  - [ ] Pieza o superficie inválida → 400 con mensaje claro
  - [ ] Los hallazgos no se pueden modificar ni borrar desde la app, solo anular (test)

### M09-T02 · API del odontograma

- **Estado**: pendiente
- **Depende de**: M09-T01
- **Descripción**: estado actual y a una fecha, historial por pieza, registrar y anular hallazgos; dentición permanente, temporal o mixta según la edad y lo registrado.
- **Criterios de aceptación**:
  - [ ] Reconstrucción a fecha con tests de casos (pieza extraída, corona sobre endodoncia…)
  - [ ] Cada consulta del odontograma queda en `audit_log`

### M09-T03 · Odontograma 2D

- **Estado**: pendiente
- **Depende de**: M09-T02, M04-T06
- **Descripción**: componente SVG por cuadrantes con las cinco superficies de cada pieza, colores por estado, selección de pieza o superficie y panel para registrar hallazgos. Sirve también para imprimir y para el PDF del presupuesto.
- **Criterios de aceptación**:
  - [ ] Utilizable solo con teclado
  - [ ] Exportable como imagen para PDF

### M09-T04 · Prueba técnica 3D y modelos

- **Estado**: pendiente
- **Depende de**: M00-T05
- **Descripción**: conseguir modelos 3D (GLB) de las 32 piezas permanentes y 20 temporales con licencia comercial, una malla por pieza nombrada por su código FDI y superficies seleccionables; prueba con Three.js en Angular (rendimiento en tablet y portátil modesto, compresión Draco, raycasting por superficie). Resultado en un ADR.
- **Criterios de aceptación**:
  - [ ] Licencia de los modelos documentada (uso comercial y modificación)
  - [ ] Carga inicial del módulo 3D por debajo de 3 MB comprimido
  - [ ] 60 fps al rotar en un portátil medio

### M09-T05 · Odontograma 3D

- **Estado**: pendiente
- **Depende de**: M09-T04, M09-T02
- **Descripción**: componente 3D cargado bajo demanda: arcadas superior e inferior, rotación y zoom, selección de pieza y superficie, colores por estado, leyenda y cambio a 2D; pinta exactamente lo que devuelve la API.
- **Criterios de aceptación**:
  - [ ] Mismo resultado visual de estados que la vista 2D
  - [ ] Si WebGL no está disponible, cae a 2D automáticamente
  - [ ] No carga Three.js hasta abrir el odontograma

### M09-T06 · Integración con la ficha y los planes

- **Estado**: pendiente
- **Depende de**: M09-T03
- **Descripción**: pestaña de odontograma en la ficha del paciente, alerta de alergias visible y acción «planificar tratamiento» sobre la pieza o superficie seleccionada (abre M10).
- **Criterios de aceptación**:
  - [ ] Desde una pieza se puede añadir un tratamiento al plan activo o crear uno nuevo
