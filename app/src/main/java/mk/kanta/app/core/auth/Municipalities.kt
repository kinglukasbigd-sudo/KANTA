package mk.kanta.app.core.auth

import java.util.Locale

/**
 * The ten municipalities of Skopje, matching supabase/seed/0001_municipalities.sql
 * id for id. Bundled rather than fetched so the sign-up step works on a flaky
 * connection and before the boundaries have been imported.
 */
data class Municipality(val id: Int, val nameMk: String, val nameSq: String, val nameEn: String) {
    fun localizedName(locale: Locale = Locale.getDefault()): String = when (locale.language) {
        "sq" -> nameSq
        "en" -> nameEn
        else -> nameMk // Macedonian is the app default (§2)
    }
}

object Municipalities {
    val all = listOf(
        Municipality(1, "Центар", "Qendër", "Centar"),
        Municipality(2, "Карпош", "Karposh", "Karpoš"),
        Municipality(3, "Аеродром", "Aerodrom", "Aerodrom"),
        Municipality(4, "Гази Баба", "Gazi Baba", "Gazi Baba"),
        Municipality(5, "Кисела Вода", "Kisella Vodë", "Kisela Voda"),
        Municipality(6, "Чаир", "Çair", "Čair"),
        Municipality(7, "Бутел", "Butel", "Butel"),
        Municipality(8, "Ѓорче Петров", "Gjorçe Petrov", "Gjorče Petrov"),
        Municipality(9, "Сарај", "Saraj", "Saraj"),
        Municipality(10, "Шуто Оризари", "Shuto Orizare", "Šuto Orizari"),
    )

    fun byId(id: Int?): Municipality? = all.firstOrNull { it.id == id }
}
