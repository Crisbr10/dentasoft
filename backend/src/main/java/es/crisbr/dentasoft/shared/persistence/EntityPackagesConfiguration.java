package es.crisbr.dentasoft.shared.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * The packages of the main persistence unit, the one the application role ({@code dentasoft_app}) uses. Listed one by
 * one instead of scanning the whole application, so the entities of the platform unit ({@code
 * es.crisbr.dentasoft.platform}, ADR 0011) never end up here.
 *
 * <p>A module with entities adds its package to this list. {@code PersistenceUnitsTests} fails if an entity belongs
 * to no unit or to both.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {"es.crisbr.dentasoft.tenancy"})
class EntityPackagesConfiguration {}
