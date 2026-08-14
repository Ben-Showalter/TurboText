package com.turbotext.app

/**
 * Set a default trusted provisioning number here before building, so a
 * freshly installed device doesn't need it entered manually in Advanced
 * Settings. Leave blank to require manual entry on-device instead.
 *
 * This is the ONLY phone number a device will accept a remote API-key
 * setup message from (see ApiKeyProvisioningHelper) — anyone who can
 * see this value in source has that same authority, so treat it with
 * the same care as the API key itself.
 *
 * Example: const val DEFAULT_TRUSTED_NUMBER = "2695551234"
 */
object ProvisioningConfig {
    const val DEFAULT_TRUSTED_NUMBER = ""
}
