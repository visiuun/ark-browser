package dev.vis.ark.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ark's password manager: saved logins, payment cards and addresses.
 *
 * Everything lives in one file encrypted with AES-256-GCM. The key is generated inside the Android
 * Keystore (hardware-backed on most phones) and never leaves it, so the file is useless if copied off
 * the device. GeckoView reads and writes it through [storage] to fill and save forms on web pages.
 */
object Vault {
    private const val TAG = "ArkVault"
    private const val KEY_ALIAS = "ark_vault_v1"
    private const val FILE = "vault.bin"

    data class Login(
        val guid: String,
        val origin: String,
        val formActionOrigin: String?,
        val httpRealm: String?,
        val username: String,
        val password: String,
        val created: Long = System.currentTimeMillis(),
        val changed: Long = System.currentTimeMillis(),
        val lastUsed: Long = 0,
        val timesUsed: Int = 0,
    ) {
        val host: String get() = hostOf(origin)
    }

    data class Card(val guid: String, val name: String, val number: String, val expMonth: String, val expYear: String, val created: Long = System.currentTimeMillis()) {
        val last4: String get() = number.filter { it.isDigit() }.takeLast(4)
        val brand: String get() = brandOf(number)
    }

    /** Address fields keyed like GeckoView's [Autocomplete.Address] (name, streetAddress, postalCode, …). */
    data class Address(val guid: String, val fields: Map<String, String>, val created: Long = System.currentTimeMillis()) {
        operator fun get(k: String) = fields[k].orEmpty()
        val label: String get() = listOf(this["name"].ifBlank { listOf(this["givenName"], this["familyName"]).filter { it.isNotBlank() }.joinToString(" ") }, this["streetAddress"].lineSequence().firstOrNull().orEmpty(), this["addressLevel2"])
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { this["email"].ifBlank { "Address" } }
    }

    val ADDRESS_KEYS = listOf(
        "name", "givenName", "additionalName", "familyName", "organization", "streetAddress",
        "addressLevel3", "addressLevel2", "addressLevel1", "postalCode", "country", "tel", "email",
    )

    val logins = mutableListOf<Login>()
    val cards = mutableListOf<Card>()
    val addresses = mutableListOf<Address>()

    /** Bumped on every change so Compose screens re-read the lists. */
    val version = mutableIntStateOf(0)

    private lateinit var file: File
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        file = File(ctx.applicationContext.filesDir, FILE)
        runCatching { load() }.onFailure { Log.e(TAG, "couldn't open the vault", it) }
        loaded = true
    }

    // ---------------------------------------------------------------- crypto

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val n = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 1 + n, blob.size - 1 - n)
    }

    // ---------------------------------------------------------------- persistence

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(String(decrypt(file.readBytes()), Charsets.UTF_8))
        logins.clear(); cards.clear(); addresses.clear()
        o.optJSONArray("logins")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                logins += Login(
                    getString("guid"), getString("origin"), optString("formActionOrigin").ifBlank { null }, optString("httpRealm").ifBlank { null },
                    optString("username"), optString("password"), optLong("created"), optLong("changed"), optLong("lastUsed"), optInt("timesUsed"),
                )
            }
        }
        o.optJSONArray("cards")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                cards += Card(getString("guid"), optString("name"), optString("number"), optString("expMonth"), optString("expYear"), optLong("created"))
            }
        }
        o.optJSONArray("addresses")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                val f = optJSONObject("fields") ?: JSONObject()
                addresses += Address(getString("guid"), f.keys().asSequence().associateWith { f.optString(it) }, optLong("created"))
            }
        }
        version.intValue++
    }

    private fun save() {
        val o = JSONObject()
        o.put("logins", JSONArray().apply {
            logins.forEach {
                put(JSONObject().put("guid", it.guid).put("origin", it.origin).put("formActionOrigin", it.formActionOrigin ?: "").put("httpRealm", it.httpRealm ?: "")
                    .put("username", it.username).put("password", it.password).put("created", it.created).put("changed", it.changed)
                    .put("lastUsed", it.lastUsed).put("timesUsed", it.timesUsed))
            }
        })
        o.put("cards", JSONArray().apply {
            cards.forEach { put(JSONObject().put("guid", it.guid).put("name", it.name).put("number", it.number).put("expMonth", it.expMonth).put("expYear", it.expYear).put("created", it.created)) }
        })
        o.put("addresses", JSONArray().apply {
            addresses.forEach { a -> put(JSONObject().put("guid", a.guid).put("fields", JSONObject(a.fields)).put("created", a.created)) }
        })
        runCatching {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeBytes(encrypt(o.toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.e(TAG, "couldn't save the vault", it) }
        version.intValue++
    }

    // ---------------------------------------------------------------- logins

    fun hostOf(origin: String?): String =
        runCatching { android.net.Uri.parse(origin).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: origin.orEmpty().lowercase()

    /** Logins Gecko may offer on [domain]: the same site, or one of its subdomains / parent domains. */
    fun loginsFor(domain: String): List<Login> {
        val d = domain.lowercase().removePrefix("www.")
        return logins.filter { val h = it.host; h == d || h.endsWith(".$d") || d.endsWith(".$h") }
    }

    fun findLogin(origin: String, username: String): Login? = logins.firstOrNull { it.host == hostOf(origin) && it.username == username }

    fun upsertLogin(origin: String, username: String, password: String, formActionOrigin: String? = null, httpRealm: String? = null, guid: String? = null) {
        val existing = guid?.let { g -> logins.indexOfFirst { it.guid == g } }?.takeIf { it >= 0 }
            ?: logins.indexOfFirst { it.host == hostOf(origin) && it.username == username }.takeIf { it >= 0 }
        val now = System.currentTimeMillis()
        if (existing != null) {
            val old = logins[existing]
            logins[existing] = old.copy(
                origin = origin.ifBlank { old.origin }, username = username, password = password,
                formActionOrigin = formActionOrigin ?: old.formActionOrigin, httpRealm = httpRealm ?: old.httpRealm, changed = now,
            )
        } else {
            logins += Login(UUID.randomUUID().toString(), origin, formActionOrigin, httpRealm, username, password, now, now)
        }
        save()
    }

    fun deleteLogin(guid: String) { if (logins.removeAll { it.guid == guid }) save() }

    fun markUsed(guid: String?) {
        val i = logins.indexOfFirst { it.guid == guid }.takeIf { it >= 0 } ?: return
        logins[i] = logins[i].copy(lastUsed = System.currentTimeMillis(), timesUsed = logins[i].timesUsed + 1)
        save()
    }

    // ---------------------------------------------------------------- cards & addresses

    fun upsertCard(name: String, number: String, month: String, year: String, guid: String? = null) {
        val digits = number.filter { it.isDigit() }
        val i = guid?.let { g -> cards.indexOfFirst { it.guid == g } }?.takeIf { it >= 0 }
            ?: cards.indexOfFirst { it.number.filter { c -> c.isDigit() } == digits }.takeIf { it >= 0 }
        val card = Card(if (i != null) cards[i].guid else UUID.randomUUID().toString(), name, digits, month, year)
        if (i != null) cards[i] = card else cards += card
        save()
    }

    fun deleteCard(guid: String) { if (cards.removeAll { it.guid == guid }) save() }

    fun upsertAddress(fields: Map<String, String>, guid: String? = null) {
        val clean = fields.filterValues { it.isNotBlank() }
        val i = guid?.let { g -> addresses.indexOfFirst { it.guid == g } }?.takeIf { it >= 0 }
            ?: addresses.indexOfFirst { it.fields == clean }.takeIf { it >= 0 }
        val a = Address(if (i != null) addresses[i].guid else UUID.randomUUID().toString(), clean)
        if (i != null) addresses[i] = a else addresses += a
        save()
    }

    fun deleteAddress(guid: String) { if (addresses.removeAll { it.guid == guid }) save() }

    fun brandOf(number: String): String {
        val n = number.filter { it.isDigit() }
        return when {
            n.startsWith("4") -> "Visa"
            n.length >= 2 && n.take(2).toInt() in 51..55 -> "Mastercard"
            n.length >= 4 && n.take(4).toInt() in 2221..2720 -> "Mastercard"
            n.startsWith("34") || n.startsWith("37") -> "American Express"
            n.startsWith("6011") || n.startsWith("65") -> "Discover"
            n.startsWith("35") -> "JCB"
            n.startsWith("62") -> "UnionPay"
            n.startsWith("36") || n.startsWith("30") || n.startsWith("38") -> "Diners Club"
            else -> "Card"
        }
    }

    // ---------------------------------------------------------------- import

    /**
     * Imports a passwords CSV exported from Chrome, Firefox, Edge, Safari, Bitwarden, 1Password, Proton Pass
     * and most other managers. Returns how many logins were added or updated.
     */
    fun importCsv(text: String): Int {
        val rows = parseCsv(text).filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return 0
        val head = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
        val url = col("url", "login_uri", "website", "origin", "hostname", "web site", "uri") ?: return 0
        val user = col("username", "login_username", "user", "login", "email", "user name") ?: return 0
        val pass = col("password", "login_password", "pass") ?: return 0
        val realm = col("httprealm")
        val action = col("formactionorigin")
        var n = 0
        for (r in rows.drop(1)) {
            val u = r.getOrNull(url)?.trim().orEmpty()
            val p = r.getOrNull(pass).orEmpty()
            if (u.isBlank() || p.isEmpty()) continue
            val origin = runCatching {
                val uri = android.net.Uri.parse(if ("://" in u) u else "https://$u")
                "${uri.scheme}://${uri.authority}"
            }.getOrNull() ?: continue
            val idx = logins.indexOfFirst { it.host == hostOf(origin) && it.username == r.getOrNull(user).orEmpty() }
            val now = System.currentTimeMillis()
            val l = Login(
                if (idx >= 0) logins[idx].guid else UUID.randomUUID().toString(), origin,
                action?.let { r.getOrNull(it) }?.ifBlank { null }, realm?.let { r.getOrNull(it) }?.ifBlank { null },
                r.getOrNull(user).orEmpty(), p, now, now,
            )
            if (idx >= 0) logins[idx] = l else logins += l
            n++
        }
        if (n > 0) save()
        return n
    }

    /** Exports every login as a Chrome/Firefox-compatible CSV (name,url,username,password). */
    fun exportCsv(): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return buildString {
            append("name,url,username,password,httpRealm,formActionOrigin\n")
            logins.sortedBy { it.host }.forEach {
                append(listOf(it.host, it.origin, it.username, it.password, it.httpRealm.orEmpty(), it.formActionOrigin.orEmpty()).joinToString(",") { s -> q(s) }).append('\n')
            }
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    // ---------------------------------------------------------------- GeckoView bridge

    private fun Login.toGecko(): Autocomplete.LoginEntry = Autocomplete.LoginEntry.Builder()
        .guid(guid).origin(origin).formActionOrigin(formActionOrigin).httpRealm(httpRealm)
        .username(username).password(password).build()

    private fun Card.toGecko(): Autocomplete.CreditCard = Autocomplete.CreditCard.Builder()
        .guid(guid).name(name).number(number).expirationMonth(expMonth).expirationYear(expYear).build()

    private fun Address.toGecko(): Autocomplete.Address = Autocomplete.Address.Builder()
        .guid(guid).name(this["name"]).givenName(this["givenName"]).additionalName(this["additionalName"]).familyName(this["familyName"])
        .organization(this["organization"]).streetAddress(this["streetAddress"]).addressLevel1(this["addressLevel1"])
        .addressLevel2(this["addressLevel2"]).addressLevel3(this["addressLevel3"]).postalCode(this["postalCode"])
        .country(this["country"]).tel(this["tel"]).email(this["email"]).build()

    fun addressFields(a: Autocomplete.Address): Map<String, String> = mapOf(
        "name" to a.name, "givenName" to a.givenName, "additionalName" to a.additionalName, "familyName" to a.familyName,
        "organization" to a.organization, "streetAddress" to a.streetAddress, "addressLevel1" to a.addressLevel1,
        "addressLevel2" to a.addressLevel2, "addressLevel3" to a.addressLevel3, "postalCode" to a.postalCode,
        "country" to a.country, "tel" to a.tel, "email" to a.email,
    ).mapValues { it.value.orEmpty() }

    /** GeckoView's view of the vault. Saving happens only after the user agrees in a save prompt. */
    val storage = object : Autocomplete.StorageDelegate {
        override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> =
            GeckoResult.fromValue(if (Prefs.fillLogins) loginsFor(domain).map { it.toGecko() }.toTypedArray() else emptyArray())

        override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> =
            GeckoResult.fromValue(if (Prefs.fillLogins) logins.map { it.toGecko() }.toTypedArray() else emptyArray())

        override fun onLoginSave(login: Autocomplete.LoginEntry) {
            val origin = login.origin ?: return
            upsertLogin(origin, login.username.orEmpty(), login.password ?: return, login.formActionOrigin, login.httpRealm, login.guid)
        }

        override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) = markUsed(login.guid)

        override fun onCreditCardFetch(): GeckoResult<Array<Autocomplete.CreditCard>> =
            GeckoResult.fromValue(if (Prefs.autofillCards) cards.map { it.toGecko() }.toTypedArray() else emptyArray())

        override fun onCreditCardSave(c: Autocomplete.CreditCard) {
            upsertCard(c.name.orEmpty(), c.number ?: return, c.expirationMonth.orEmpty(), c.expirationYear.orEmpty(), c.guid)
        }

        override fun onAddressFetch(): GeckoResult<Array<Autocomplete.Address>> =
            GeckoResult.fromValue(if (Prefs.autofillAddresses) addresses.map { it.toGecko() }.toTypedArray() else emptyArray())

        override fun onAddressSave(a: Autocomplete.Address) = upsertAddress(addressFields(a), a.guid)
    }
}
