# 0004 — Firma de documentos con app Expo en tablet

Fecha: 2026-11-23 · Estado: aceptada

## Contexto

Las clínicas necesitan que el paciente firme consentimientos informados y la aceptación de presupuestos en la propia clínica.

## Decisión

- App **Expo (React Native)** en `mobile/`, instalada en tablets de la clínica, emparejadas con un código de un solo uso y con token propio revocable.
- La web crea una solicitud de firma; la tablet la recibe en tiempo real, muestra el PDF completo y recoge la firma manuscrita.
- El backend incrusta la firma en el PDF, calcula su SHA-256, lo guarda con bloqueo de objeto en S3 y registra la evidencia (fecha, tablet, usuario, versión y huella del documento).
- La tablet funciona en modo quiosco y no guarda datos de pacientes al terminar.
- Es una firma electrónica simple con evidencias; si se necesita firma avanzada, se integra un proveedor.

## Consecuencias

- Sin coste por firma ni dependencia de terceros.
- Hay que mantener una app móvil y revisar si cae bajo el Reglamento de Ciberresiliencia antes de distribuirla.

## Alternativas descartadas

- Firma en la propia web desde una tablet con navegador: menos control del dispositivo (quiosco, caché).
- Proveedor de firma avanzada desde el inicio: coste por firma y más complejidad para el uso habitual.
