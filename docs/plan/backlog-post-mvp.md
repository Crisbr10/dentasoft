# Backlog posterior al MVP

Funcionalidades decididas pero fuera de la primera versión. Cuando una pase a desarrollarse, se convierte en un módulo `Mxx` con su fichero y tareas.

| Prioridad | Funcionalidad | Notas |
| --- | --- | --- |
| Alta | **Facturación con Verifactu** | Obligatorio para sociedades desde el 1 de enero de 2027 y para autónomos desde el 1 de julio de 2027. Facturas inmutables con hash encadenado y QR, rectificativas, series por clínica. Valorar API de un proveedor Verifactu. Si una clínica necesita facturar con Dentasoft desde el primer día, adelantarlo antes de M13 |
| Alta | **Cobro del SaaS a las clínicas** | Stripe: suscripciones, SEPA y facturas automáticas |
| Media | **Migración desde medicAssistant** | Importación de pacientes, historiales y documentos de la primera clínica |
| Media | **Catalán** | Traducción de `ca.json` en frontend, emails y PDF (la app ya está preparada) |
| Media | **IA fase 2** | Recordatorios inteligentes, respuestas a preguntas frecuentes por WhatsApp, dictado de notas. Ver ADR 0005 |
| Baja | **IA fase 3** | Asistente de agenda por WhatsApp, búsqueda en lenguaje natural con `pgvector` |
| Baja | **Informes y estadísticas** | Actividad, producción por profesional, aceptación de presupuestos |
| Baja | **Kubernetes** | Cuando haya varias réplicas o mucha carga: manifiestos o Helm sobre las mismas imágenes |
| Baja | **Portal o app del paciente** | Citas, documentos y presupuestos desde el móvil |
| Baja | **Exportación FHIR** | Exportar datos de un paciente en formato HL7 FHIR |
