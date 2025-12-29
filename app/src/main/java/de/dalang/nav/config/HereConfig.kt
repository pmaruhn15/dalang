package de.dalang.nav.config

/**
 * HERE API Konfiguration
 *
 * Um HERE API zu nutzen:
 * 1. Registriere dich auf https://developer.here.com
 * 2. Erstelle ein Projekt und hole dir einen API Key
 * 3. Trage den API Key hier ein
 */
object HereConfig {
    // TODO: Ersetze mit deinem HERE API Key
    const val API_KEY = ""

    // HERE Routing API v8 Base URL
    const val ROUTING_BASE_URL = "https://router.hereapi.com/v8"

    fun isConfigured(): Boolean = API_KEY.isNotBlank()
}
